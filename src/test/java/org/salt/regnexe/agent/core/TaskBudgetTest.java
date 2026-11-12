package org.salt.regnexe.agent.core;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.param.*;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.*;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.salt.regnexe.agent.core.task.state.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.*;

/** Real loop, fixed local model responses. Never uses a network actuator. */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class TaskBudgetTest {
    @Autowired
    private RegnexeAgentBuilder builder;
    private static class Model extends BaseChatModel {
        String mode = "normal";
        int calls, executeCalls;
        List<String> prompts = new ArrayList<>();
        @Override
        public AIMessage invoke(Object input) {
            calls++;
            String prompt = String.valueOf(input);
            prompts.add(prompt);
            if (prompt.contains("You are a task planner")) {
                if (mode.equals("plan-error")) {
                    throw new IllegalStateException("controlled transport error");
                }
                return answer("{\"selectedCapabilityIds\":[\"target\"],\"narrative\":\"do task\",\"iterationsHint\":500}");
            }
            if (prompt.contains("You are a reflection judge")) {
                if (mode.equals("reflect-invalid")) {
                    return answer("invalid json");
                }
                return answer("{\"action\":\"" + (mode.equals("continue") ? "CONTINUE" : "FINISH") + "\",\"reason\":\"local\"}");
            }
            executeCalls++;
            if (mode.equals("execute-error")) {
                throw new IllegalStateException("controlled model error");
            }
            if (mode.equals("forever") || (mode.equals("tool") && executeCalls == 1)) {
                var function = new AiChatOutput.ToolCall.Function();
                function.setName("probe");
                function.setArguments("{}");
                var call = new AiChatOutput.ToolCall();
                call.setId("call-" + calls);
                call.setType("function");
                call.setFunction(function);
                return ToolMessage.builder().role("assistant").content("").toolCalls(List.of(call)).build();
            }
            return answer(mode.equals("large-result") ? "x".repeat(5000) : "local result");
        }
        private AIMessage answer(String text) {
            return AIMessage.builder().content(text).build();
        }
        @Override
        public void otherInformation(AiChatInput input) {
        }
        @Override
        public Class<? extends AiChatActuator> getActuator() {
            throw new AssertionError("No network");
        }
        @Override
        public BaseChatModel copy() {
            return this;
        }
        @Override
        public BaseChatModel withJsonMode() {
            return this;
        }
    }

    private ExecutionBudget budget(int iterations, int calls) {
        var budget = new ExecutionBudget();
        budget.setHardMaxIterations(iterations);
        budget.setMaxModelCalls(calls);
        return budget;
    }

    private AgentResult run(Model model, ExecutionBudget budget, Tool... tools) {
        return builder.withLlmProvider(spec -> model).withMaxRounds(1).withExecutionBudget(budget)
                .withSkill(SkillConfig.builder().name("target").description("test").systemPrompt("local instructions").build())
                .withTool(tools).build().executeSkill("target", "local task", "budget-session", null);
    }

    private Tool tool(Function<Object,Object> action) {
        return Tool.builder().name("probe").description("controlled local probe").params("").func(action).build();
    }

    @Test
    public void countsPlanExecuteAndReflectAttempts() {
        var model = new Model();
        var result = run(model, budget(4,6));
        assertEquals(TaskStatus.FINISHED, result.getStatus());
        assertEquals(Map.of("plan",1,"execute",1,"reflect",1), result.getState().getModelCallAttempts());
        assertEquals(3,model.calls);
    }

    @Test
    public void dynamicHintCannotRaiseHardIterationCap() {
        var model = new Model();
        model.mode="forever";
        var tools = new AtomicInteger();
        var result=run(model,budget(2,10),tool(args -> {
            tools.incrementAndGet();
            return "Exit: 0\nlocal";
        }));
        assertEquals(TaskStatus.TIMEOUT,result.getStatus());
        assertEquals("ITERATION_LIMIT",result.getState().getTerminationReason());
        assertEquals(Integer.valueOf(2), result.getState().getRounds().get(0).getAppliedMaxIterations());
        assertEquals(2,tools.get());
        assertEquals(3,model.calls);
    }

    @Test
    public void lastPermittedIterationCanFinishAndReceivesWrapUpNotice() {
        var model=new Model();
        model.mode="tool";
        var result=run(model,budget(2,6),tool(args -> "Exit: 0\nlocal"));
        assertEquals(TaskStatus.FINISHED,result.getStatus());
        assertEquals(4,model.calls);
        assertTrue(model.prompts.stream().anyMatch(p -> p.contains("[SYSTEM NOTICE] Only 1")));
    }

    @Test
    public void cumulativeCallLimitStopsBeforeNextRequestAndPreservesPartialTools() {
        var model=new Model();
        model.mode="forever";
        var result=run(model,budget(8,3),tool(args -> "Exit: 0\npartial"));
        assertEquals(TaskStatus.TIMEOUT,result.getStatus());
        assertEquals("MODEL_CALL_LIMIT",result.getState().getTerminationReason());
        assertEquals(3,model.calls);
        assertEquals(2,result.getState().getPriorSteps().size());
        assertEquals(2,result.getState().getRounds().get(0).getExecutionResult().getToolExecutions().size());
    }

    @Test
    public void plannerTransportRetriesConsumeSameTotalBudget() {
        var model=new Model();
        model.mode="plan-error";
        var result=run(model,budget(4,2));
        assertEquals("MODEL_CALL_LIMIT",result.getState().getTerminationReason());
        assertEquals(2,model.calls);
        assertEquals(Map.of("plan",2),result.getState().getModelCallAttempts());
    }

    @Test
    public void reflectParseRetriesConsumeSameTotalBudget() {
        var model=new Model();
        model.mode="reflect-invalid";
        var result=run(model,budget(4,4));
        assertEquals("MODEL_CALL_LIMIT",result.getState().getTerminationReason());
        assertEquals(4,model.calls);
        assertEquals(Integer.valueOf(2),result.getState().getModelCallAttempts().get("reflect"));
    }

    @Test
    public void failedExecuteIsNotAutomaticallyReplayed() {
        var model=new Model();
        model.mode="execute-error";
        var result=run(model,budget(4,6));
        assertEquals(TaskStatus.FAILED,result.getStatus());
        assertEquals("MODEL_ERROR",result.getState().getTerminationReason());
        assertEquals(2,model.calls);
        assertEquals(1,model.executeCalls);
    }

    @Test
    public void outerRoundLimitIsDistinctFromIterationLimit() {
        var model=new Model();
        model.mode="continue";
        var result=run(model,budget(4,6));
        assertEquals(TaskStatus.TIMEOUT,result.getStatus());
        assertEquals("ROUND_LIMIT",result.getState().getTerminationReason());
        assertEquals(3,model.calls);
    }

    @Test
    public void toolExitTimeoutAndExceptionHaveSeparateOutcomes() {
        for (String observation:List.of("Exit: 7\ncontrolled error","Error: timed out after 0.1s\npartial")) {
            var model=new Model();
            model.mode="tool";
            var result=run(model,budget(3,6),tool(args -> observation));
            var record=result.getState().getRounds().get(0).getExecutionResult().getToolExecutions().get(0);
            assertEquals(observation.startsWith("Exit")?"TOOL_NONZERO_EXIT":"TOOL_TIMEOUT",record.getOutcome());
            assertEquals(observation,record.getObservation());
        }
        var model=new Model();
        model.mode="tool";
        var attempts=new AtomicInteger();
        var result=run(model,budget(3,6),tool(args -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("controlled failure");
        }));
        assertEquals(1,attempts.get());
        assertEquals("TOOL_ERROR",result.getState().getRounds().get(0).getExecutionResult().getToolExecutions().get(0).getOutcome());
    }

    @Test
    public void userPauseHasItsOwnReasonAndDoesNotSpendExecutionCalls() {
        var model=new Model();
        var reference=new AtomicReference<RegnexeAgent>();
        var agent=builder.withLlmProvider(spec -> model).withMaxRounds(1).withExecutionBudget(budget(4,6))
                .withSkill(SkillConfig.builder().name("target").description("test").systemPrompt("local").build())
                .withEventListener(event -> {
                    if(event.getType()==EventType.EXECUTION_STARTED) reference.get().pause();
        }).build();
        reference.set(agent);
        var result=agent.executeSkill("target","local task","pause-session",null);
        assertEquals(TaskStatus.PAUSED,result.getStatus());
        assertEquals("USER_INTERRUPTED",result.getState().getTerminationReason());
        assertEquals(1,model.calls);
    }

    @Test
    public void consecutiveToolFailureAbortIsNotAnIterationLimit() {
        var model=new Model();
        model.mode="tool";
        var result=builder.withLlmProvider(spec -> model).withMaxRounds(1).withExecutionBudget(budget(4,6))
                .withMaxConsecutiveToolFailures(1)
                .withSkill(SkillConfig.builder().name("target").description("test").systemPrompt("local").build())
                .withTool(tool(args -> {
            throw new IllegalStateException("controlled failure");
        }))
                .build().executeSkill("target","local task","failure-session",null);
        assertEquals(TaskStatus.FAILED,result.getStatus());
        assertEquals("TOOL_FAILURE_LIMIT",result.getState().getTerminationReason());
        assertEquals(2,model.calls);
    }

    @Test
    public void sessionSummaryCallsCannotEscapeTaskCallBudget() {
        var model=new Model();
        model.mode="large-result";
        var result=builder.withLlmProvider(spec -> model).withMaxRounds(1).withExecutionBudget(budget(4,3))
                .withSessionCompactPeriod(1).withSkill(SkillConfig.builder().name("target").description("test")
                        .systemPrompt("local instructions").build()).build().executeSkill("target","local task","summary-session",null);
        assertEquals(3,model.calls);
        assertEquals("MODEL_CALL_LIMIT", result.getState().getTerminationReason());
        assertTrue(result.getState().getModelCallAttempts().values().stream().mapToInt(Integer::intValue).sum()<=3);
    }
}
