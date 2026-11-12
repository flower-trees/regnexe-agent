package org.salt.regnexe.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.*;
import org.salt.regnexe.agent.core.marketplace.Marketplace;
import org.salt.regnexe.agent.core.marketplace.SimpleMarketplace;
import org.salt.regnexe.agent.core.marketplace.capability.*;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.task.state.*;
import org.salt.regnexe.agent.core.task.state.plan.PlanOutput;
import org.salt.regnexe.agent.core.task.store.InMemoryTaskStore;
import org.salt.regnexe.agent.core.task.worker.Reflector;
import org.salt.regnexe.agent.core.task.worker.RequiredSkillPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.*;

/** Actual shared task loop with an in-memory model; no HTTP, credentials or business tools. */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class RequiredSkillExecutionTest {
    @Autowired
    private RegnexeAgentBuilder builder;

    private SimpleMarketplace market() {
        var market = new SimpleMarketplace();
        for (String id : List.of("target", "other")) {
            var config = SkillConfig.builder().name(id).description(id)
                    .systemPrompt("INSTRUCTIONS_" + id).build();
            market.install(PluginDescriptor.builder().pluginId(id).name(id)
                    .capabilities(List.of(CapabilityDescriptor.builder().capabilityId(id).pluginId(id)
                            .type(CapabilityType.SKILL).skillConfig(config).build())).build());
        }
        return market;
    }

    private TaskRequest request() {
        var request = new TaskRequest();
        request.setGoal("same input");
        request.setRequiredSkillId("target");
        request.setSessionId("session");
        return request;
    }

    private static class FakeModel extends BaseChatModel {
        final List<String> prompts = new ArrayList<>();
        final List<String> toolDefinitions = new ArrayList<>();
        int calls;
        final boolean omit;
        FakeModel(boolean omit) {
            this.omit = omit;
        }
        @Override
        public AIMessage invoke(Object input) {
            prompts.add(String.valueOf(input));
            toolDefinitions.add(String.valueOf(getTools()));
            int call = calls++ % 3;
            String text = call == 0 ? "{\"selectedCapabilityIds\":" + (omit ? "[]" : "[\"target\"]")
                    + ",\"narrative\":\"do task\",\"finalAnswerRequirements\":[]}"
                    : call == 1 ? "finished locally" : "{\"action\":\"FINISH\",\"reason\":\"done\"}";
            return AIMessage.builder().content(text).build();
        }
        @Override
        public void otherInformation(AiChatInput input) {
        }
        @Override
        public Class<? extends AiChatActuator> getActuator() {
            throw new AssertionError("No network actuator");
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

    @Test
    public void rejectsUnknownDisabledAndNonSkillBeforeAnyModelCall() {
        var market = market();
        var model = new FakeModel(false);
        var agent = builder.withPluginMarket(market).withLlmProvider(spec -> model).build();
        assertThrows(IllegalArgumentException.class, () -> agent.executeSkill("missing", "x", null, null));
        market.disable("target");
        assertThrows(IllegalArgumentException.class, () -> agent.executeSkill("target", "x", null, null));
        market.install(PluginDescriptor.builder().pluginId("invalid").name("invalid")
                .capabilities(List.of(CapabilityDescriptor.builder().pluginId("invalid").capabilityId("invalid")
                        .type(CapabilityType.MCP_TOOL).build())).build());
        assertThrows(IllegalArgumentException.class, () -> agent.executeSkill("invalid", "x", null, null));
        market.install(PluginDescriptor.builder().pluginId("unloaded").name("unloaded")
                .capabilities(List.of(CapabilityDescriptor.builder().pluginId("unloaded").capabilityId("unloaded")
                        .type(CapabilityType.SKILL).build())).build());
        assertThrows(IllegalArgumentException.class, () -> agent.executeSkill("unloaded", "x", null, null));
        assertEquals(0, model.calls);
    }

    @Test
    public void candidatesAndPlanCannotReplaceOrExpandRequiredSkill() {
        var request = request();
        assertEquals(List.of("target"), RequiredSkillPolicy.candidates(request, market()).stream()
                .map(c -> c.getCapabilityId()).toList());
        var plan = new PlanOutput();
        plan.setSelectedCapabilityIds(List.of("other"));
        RequiredSkillPolicy.enforcePlan(request, plan);
        assertEquals(List.of("target"), plan.getSelectedCapabilityIds());
        assertEquals("same input", plan.getCapabilityInputDescriptions().get("target"));
    }

    @Test
    public void explicitAndAutoRunSamePersistedPhasesAndSkillBody() {
        for (boolean explicit : List.of(true, false)) {
            var model = new FakeModel(explicit);
            // explicit Planner deliberately omits the Skill
            var store = new InMemoryTaskStore();
            List<EventType> events = new ArrayList<>();
            var agent = builder.withPluginMarket(market()).withLlmProvider(spec -> model)
                    .withTaskStore(store).withEventListener(e -> events.add(e.getType())).build();
            var result = explicit ? agent.executeSkill("target", "same input", "session", "/target same input")
                    : agent.execute("same input");
            assertEquals(TaskStatus.FINISHED, result.getStatus());
            assertNotNull(result.getState());
            assertTrue(store.load(result.getTaskId()).isPresent());
            for (EventType phase : List.of(EventType.SEARCH_COMPLETED, EventType.PLAN_COMPLETED,
                    EventType.EXECUTION_COMPLETED, EventType.REFLECTION_COMPLETED)) assertTrue(events.contains(phase));
            assertEquals(3, model.calls);
            assertTrue(model.prompts.get(1).contains("INSTRUCTIONS_target"));
            assertFalse(model.prompts.get(1).contains("INSTRUCTIONS_other"));
            assertEquals(explicit ? "target" : null, result.getState().getRequest().getRequiredSkillId());
        }
    }

    @Test
    public void constraintSurvivesSerializationResumeAndSupplement() throws Exception {
        var mapper = new ObjectMapper();
        var request = mapper.readValue(mapper.writeValueAsBytes(request()), TaskRequest.class);
        var state = new TaskExecutionState();
        state.setTaskId("paused");
        state.setSessionId("session");
        state.setRequest(request);
        state.setStatus(TaskStatus.PAUSED);
        state.setMaxRounds(1);
        var store = new InMemoryTaskStore();
        store.save(state);
        var model = new FakeModel(true);
        var agent = builder.withPluginMarket(market()).withLlmProvider(spec -> model).withTaskStore(store).build();
        var result = agent.resumeTask("session", "paused", "additional instruction");
        assertEquals(TaskStatus.FINISHED, result.getStatus());
        assertEquals("target", result.getState().getRequest().getRequiredSkillId());
        assertTrue(result.getState().getRequest().getGoal().contains("additional instruction"));
        assertTrue(model.prompts.get(1).contains("INSTRUCTIONS_target"));
    }

    @Test
    public void explicitSkillHasBaseToolsButNotDeclaredExtraTools() {
        var market = market();
        market.resolveDescriptor("target").getSkillConfig().setAllowedTools(List.of("extra_probe"));
        var extra = Tool.builder().name("extra_probe").description("extra")
                .params("").func(x -> "extra").build();
        market.install(PluginDescriptor.builder().pluginId("extras").name("extras")
                .capabilities(List.of(CapabilityDescriptor.builder().pluginId("extras")
                        .capabilityId("extra_probe").type(CapabilityType.MCP_TOOL).tool(extra).build())).build());
        var base = Tool.builder().name("base_probe").description("base")
                .params("").func(x -> "base").build();
        var model = new FakeModel(true);
        var agent = builder.withPluginMarket(market).withLlmProvider(spec -> model).withTool(base).build();
        var result = agent.executeSkill("target", "same input", "session", null);
        assertEquals(TaskStatus.FINISHED, result.getStatus());
        assertTrue(model.toolDefinitions.get(1).contains("base_probe"));
        assertFalse(model.toolDefinitions.get(1).contains("extra_probe"));
    }

    @Test
    public void pauseAndRebuiltAgentResumeUseSharedTaskState() {
        var store = new InMemoryTaskStore();
        var ref = new AtomicReference<RegnexeAgent>();
        var firstModel = new FakeModel(true);
        var first = builder.withPluginMarket(market()).withLlmProvider(spec -> firstModel)
                .withTaskStore(store).withEventListener(event -> {
                    if (event.getType() == EventType.EXECUTION_STARTED) {
                        ref.get().pause();
                    }
        }).build();
        ref.set(first);
        var paused = first.executeSkill("target", "same input", "session", "/target same input");
        assertEquals(TaskStatus.PAUSED, paused.getStatus());
        assertEquals("target", store.load(paused.getTaskId()).orElseThrow().getRequest().getRequiredSkillId());
        var resumedModel = new FakeModel(true);
        var rebuilt = builder.withPluginMarket(market()).withLlmProvider(spec -> resumedModel).withTaskStore(store).build();
        var resumed = rebuilt.resumeTask("session", paused.getTaskId(), "extra instruction");
        assertEquals(TaskStatus.FINISHED, resumed.getStatus());
        assertEquals("target", resumed.getState().getRequest().getRequiredSkillId());
        assertTrue(resumedModel.prompts.get(1).contains("INSTRUCTIONS_target"));
    }

    @Test
    public void guardStillRequiresCallsForActualToolCapabilities() throws Exception {
        var market = market();
        var state = new TaskExecutionState();
        var round = new RoundRecord();
        var plan = new PlanOutput();
        round.setPlan(plan);
        var reflector = new Reflector();
        var guard = reflector.getClass().getDeclaredMethod("evaluateGuardRules", RoundRecord.class,
                TaskExecutionState.class, Marketplace.class);
        guard.setAccessible(true);
        plan.setSelectedCapabilityIds(List.of("target"));
        assertNull(guard.invoke(reflector, round, state, market));
        plan.setSelectedCapabilityIds(List.of("missing-tool"));
        assertNotNull(guard.invoke(reflector, round, state, market));
    }

    @Test
    public void resumeRejectsNowDisabledSkillWithoutModelCall() {
        var market = market();
        market.disable("target");
        var state = new TaskExecutionState();
        state.setTaskId("paused");
        state.setSessionId("session");
        state.setRequest(request());
        state.setStatus(TaskStatus.PAUSED);
        var store = new InMemoryTaskStore();
        store.save(state);
        var model = new FakeModel(false);
        var agent = builder.withPluginMarket(market).withLlmProvider(spec -> model).withTaskStore(store).build();
        assertThrows(IllegalArgumentException.class, () -> agent.resumeTask("session", "paused", "extra"));
        assertEquals(0, model.calls);
        assertEquals("same input", state.getRequest().getGoal());
    }
}
