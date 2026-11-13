package org.salt.regnexe.agent.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.regnexe.agent.core.common.enums.ReflectionAction;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.task.state.ExecutionBudget;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.TaskRequest;
import org.salt.regnexe.agent.core.task.state.reflection.GroundingReview;
import org.salt.regnexe.agent.core.task.state.reflection.GroundingReview.ClaimCheck;
import org.salt.regnexe.agent.core.task.state.reflection.ReflectionDecision;
import org.salt.regnexe.agent.core.task.worker.FactualGrounding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import static org.junit.Assert.*;

/** Historical P07 failure, protocol guards, and actual loop with fixed local responses. */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class FactualGroundingTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired
    private RegnexeAgentBuilder builder;

    private Map<String, Object> sample() throws Exception {
        try (var input = getClass().getResourceAsStream("/factual-grounding/p07-failure.json")) {
            return JSON.readValue(input, new TypeReference<Map<String, Object>>() {});
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> facts() throws Exception {
        return (Map<String, Object>) sample().get("facts");
    }

    private TaskExecutionState state() throws Exception {
        var request = new TaskRequest();
        request.setAuthoritativeFacts(facts());
        var state = new TaskExecutionState();
        state.setRequest(request);
        return state;
    }

    private ReflectionDecision review(String answer) {
        var decision = new ReflectionDecision();
        decision.setAction(ReflectionAction.FINISH);
        var review = new GroundingReview();
        List<ClaimCheck> checks = new ArrayList<>();
        for (int i = 0; i < FactualGrounding.claims(answer).size(); i++) {
            var check = new ClaimCheck();
            check.setClaimId(i + 1);
            check.setAssessment("SUPPORTED");
            check.setSourcePointers(List.of("/name", "/product_kind", "/intended_use", "/payload_kg", "/reach_mm", "/capabilities/0"));
            checks.add(check);
        }
        review.setChecks(checks);
        decision.setGroundingReview(review);
        return decision;
    }

    private void flagUnsupported(ReflectionDecision decision, String answer) {
        List<String> clauses = FactualGrounding.claims(answer);
        for (ClaimCheck check : decision.getGroundingReview().getChecks()) {
            String clause = clauses.get(check.getClaimId() - 1);
            if (clause.contains("结构紧凑") || clause.contains("复杂程序") || clause.contains("门槛低") || clause.contains("桌面级")) {
                check.setAssessment("UNSUPPORTED");
                check.setSourcePointers(List.of());
            }
        }
    }

    @Test
    public void oldFinishWithoutEvidenceIsRejectedForHistoricalAnswer() throws Exception {
        var state = state();
        var decision = new ReflectionDecision();
        decision.setAction(ReflectionAction.FINISH);
        FactualGrounding.enforce(state, (String) sample().get("failedAnswer"), decision);
        assertEquals(ReflectionAction.CONTINUE, decision.getAction());
        assertEquals("FACTUAL_REVIEW_INCOMPLETE", state.getTerminationReason());
    }

    @Test
    public void unsupportedClaimsOverrideEvenAConflictingFinish() throws Exception {
        String answer = (String) sample().get("failedAnswer");
        var decision = review(answer);
        flagUnsupported(decision, answer);
        var state = state();
        FactualGrounding.enforce(state, answer, decision);
        assertEquals(ReflectionAction.CONTINUE, decision.getAction());
        assertEquals("UNSUPPORTED_CLAIMS", state.getTerminationReason());
        assertTrue(decision.getHintForNext().getPlanAdjustment().contains("结构紧凑"));
    }

    @Test
    public void referenceParaphraseWithCompleteEvidenceCanFinish() throws Exception {
        String answer = (String) sample().get("referenceAnswer");
        var state = state();
        var decision = review(answer);
        FactualGrounding.enforce(state, answer, decision);
        assertEquals(ReflectionAction.FINISH, decision.getAction());
        assertNull(state.getTerminationReason());
    }

    @Test
    public void omissionsDuplicatesAndUnknownAssessmentsCannotFinish() throws Exception {
        String answer = (String) sample().get("failedAnswer");
        for (String mode : List.of("omit", "duplicate", "unknown", "uncertain")) {
            var decision = review(answer);
            var checks = decision.getGroundingReview().getChecks();
            switch (mode) {
                case "omit" -> checks.remove(checks.size() - 1);
                case "duplicate" -> checks.add(checks.get(0));
                case "unknown" -> checks.get(0).setAssessment("PLAUSIBLE");
                case "uncertain" -> checks.get(0).setAssessment("UNCERTAIN");
            }
            FactualGrounding.enforce(state(), answer, decision);
            assertEquals(mode, ReflectionAction.CONTINUE, decision.getAction());
        }
    }

    @Test
    public void draftAndUnknownSourcePointersCannotServeAsEvidence() throws Exception {
        String answer = (String) sample().get("referenceAnswer");
        for (String pointer : List.of("/before/description", "/unknown/0", "", "/capabilities")) {
            var decision = review(answer);
            decision.getGroundingReview().getChecks().get(0).setSourcePointers(List.of(pointer));
            FactualGrounding.enforce(state(), answer, decision);
            assertEquals(pointer, ReflectionAction.CONTINUE, decision.getAction());
        }
    }

    @Test
    public void factsSnapshotIsIndependentAndEmptyFactsFail() throws Exception {
        Map<String, Object> facts = new HashMap<>(facts());
        var copy = FactualGrounding.snapshot(facts);
        facts.put("name", "changed");
        assertNotEquals(facts.get("name"), copy.get("name"));
        assertThrows(IllegalArgumentException.class, () -> FactualGrounding.snapshot(Map.of()));
    }

    @Test
    public void normalTasksKeepLegacyReflectionBehavior() {
        var state = new TaskExecutionState();
        state.setRequest(new TaskRequest());
        var decision = new ReflectionDecision();
        decision.setAction(ReflectionAction.FINISH);
        FactualGrounding.enforce(state, "hello", decision);
        assertEquals(ReflectionAction.FINISH, decision.getAction());
        assertEquals("", FactualGrounding.instructions(null));
    }

    @Test
    public void sourceAndClaimIdsOnlyReachReflectorAndFailureSurvivesRoundLimit() throws Exception {
        var fixture = sample();
        String answer = (String) fixture.get("failedAnswer");
        var review = review(answer);
        flagUnsupported(review, answer);
        String reflection = JSON.writeValueAsString(review);
        List<String> prompts = new ArrayList<>();
        BaseChatModel model = new BaseChatModel() {
            @Override
            public AIMessage invoke(Object input) {
                prompts.add(String.valueOf(input));
                String content = switch (prompts.size()) {
                    case 1 -> "{\"selectedCapabilityIds\":[\"target\"],\"narrative\":\"rewrite\"}";
                    case 2 -> answer;
                    default -> reflection;
                };
                return AIMessage.builder().content(content).build();
            }
            @Override
            public void otherInformation(AiChatInput input) {}
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
        };
        var budget = new ExecutionBudget();
        budget.setHardMaxIterations(4);
        budget.setMaxModelCalls(6);
        var result = builder.withLlmProvider(spec -> model).withMaxRounds(1)
                .withExecutionBudget(budget).withAuthoritativeFacts(facts())
                .withSkill(SkillConfig.builder().name("target").description("test").systemPrompt("original skill instructions").build())
                .build().executeSkill("target", (String) fixture.get("goal"), "grounding-test", null);
        assertEquals(TaskStatus.TIMEOUT, result.getStatus());
        assertEquals("UNSUPPORTED_CLAIMS", result.getState().getTerminationReason());
        assertEquals(3, prompts.size());
        assertFalse(prompts.get(0).contains("Authoritative facts (JSON pointer root)"));
        assertFalse(prompts.get(1).contains("Authoritative facts (JSON pointer root)"));
        assertTrue(prompts.get(2).contains("Authoritative facts (JSON pointer root)"));
        assertTrue(prompts.get(2).contains("结构紧凑"));
        assertEquals(answer, result.getState().getRounds().get(0).getExecutionResult().getFinalText());
        assertEquals(facts(), result.getState().getRequest().getAuthoritativeFacts());
    }
}
