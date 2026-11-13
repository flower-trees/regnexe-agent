package org.salt.regnexe.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.salt.regnexe.agent.core.common.enums.ExecutionStatus;
import org.salt.regnexe.agent.core.task.state.OfflineTextReview;
import org.salt.regnexe.agent.core.task.state.RoundRecord;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.TaskRequest;
import org.salt.regnexe.agent.core.task.state.execution.ExecutionOutput;
import org.salt.regnexe.agent.core.task.state.execution.ToolExecutionRecord;
import org.salt.regnexe.agent.core.task.worker.CompactReflectionPrompt;
import org.salt.regnexe.agent.core.task.worker.FactualGrounding;
import static org.junit.Assert.*;

public class CompactReflectionPromptTest {
    private TaskExecutionState state() {
        var context = new OfflineTextReview();
        context.setTaskSummary("Rewrite offline");
        context.setAcceptanceCriteria(List.of("Return JSON with a description"));
        var request = new TaskRequest();
        request.setOfflineTextReview(context);
        request.setAuthoritativeFacts(Map.of("name", "A6", "payload_kg", 3));
        var execution = new ExecutionOutput();
        execution.setStatus(ExecutionStatus.SUCCESS);
        var round = new RoundRecord();
        round.setExecutionResult(execution);
        var state = new TaskExecutionState();
        state.setRequest(request);
        state.setCurrentRound(1);
        state.setRounds(new ArrayList<>(List.of(round)));
        return state;
    }

    @Test
    public void annotationRetainsEveryCharacterAndClaimOrder() throws Exception {
        var state = state();
        String text = "  A6，三公斤。\n\n 拖拽示教； next!";
        String output = new ObjectMapper().writeValueAsString(Map.of("description", text));
        assertTrue(CompactReflectionPrompt.eligible(state, state.getRounds().get(0), output, List.of()));
        String prompt = CompactReflectionPrompt.userPrompt(state, output);
        String rendered = prompt.substring(prompt.indexOf("(blank=false):\n") + "(blank=false):\n".length());
        assertEquals(text + "\n", rendered.replaceAll("\\[claim:\\d+\\] ", ""));
        var clauses = FactualGrounding.claims(output);
        for (int i = 0; i < clauses.size(); i++) {
            assertTrue(prompt.contains("[claim:" + (i + 1) + "] " + clauses.get(i)));
        }
        assertFalse(prompt.contains("[claim:" + (clauses.size() + 1) + "]"));
        assertTrue(prompt.contains("\"payload_kg\":3"));
    }

    @Test
    public void unsafeOrAmbiguousContextsFallBack() {
        var state = state();
        var round = state.getRounds().get(0);
        for (String output : List.of("invalid", "{}", "{\"x\":3}", "{\"x\":{}}", "{\"x\":\"a\",\"x\":\"b\"}",
                "{\"x\":\"a\"} {}", "{\"x\":\"[claim:1]\"}")) {
            assertFalse(output, CompactReflectionPrompt.eligible(state, round, output, List.of()));
        }
        String output = "{\"x\":\"A6\"}";
        assertFalse(CompactReflectionPrompt.eligible(state, round, output, List.of("history")));
        state.setCurrentRound(2);
        assertFalse(CompactReflectionPrompt.eligible(state, round, output, List.of()));
        state.setCurrentRound(1);
        round.getExecutionResult().setStatus(ExecutionStatus.FAILED);
        assertFalse(CompactReflectionPrompt.eligible(state, round, output, List.of()));
        round.getExecutionResult().setStatus(ExecutionStatus.SUCCESS);
        round.getExecutionResult().setToolExecutions(List.of(new ToolExecutionRecord()));
        assertFalse(CompactReflectionPrompt.eligible(state, round, output, List.of()));
        round.getExecutionResult().setToolExecutions(List.of());
        state.getRequest().setAuthoritativeFacts(null);
        assertFalse(CompactReflectionPrompt.eligible(state, round, output, List.of()));
        round.setExecutionResult(null);
        assertFalse(CompactReflectionPrompt.eligible(state, round, output, List.of()));
        state.getRequest().setOfflineTextReview(null);
        assertFalse(CompactReflectionPrompt.eligible(state, round, output, List.of()));
    }

    @Test
    public void historicalFailureKeepsAllThirteenClauses() throws Exception {
        try (var input = getClass().getResourceAsStream("/factual-grounding/p07-failure.json")) {
            var fixture = new ObjectMapper().readTree(input);
            String answer = fixture.get("failedAnswer").asText();
            var state = state();
            String prompt = CompactReflectionPrompt.userPrompt(state, answer);
            var clauses = FactualGrounding.claims(answer);
            assertEquals(13, clauses.size());
            for (int i = 0; i < clauses.size(); i++) {
                assertTrue(prompt.contains("[claim:" + (i + 1) + "] " + clauses.get(i)));
            }
            assertTrue(prompt.contains("Field \"detail_description\""));
        }
    }

    @Test
    public void criteriaAreValidatedAndCopied() {
        var original = state().getRequest().getOfflineTextReview();
        var copy = OfflineTextReview.snapshot(original);
        original.setAcceptanceCriteria(List.of("changed"));
        assertEquals(List.of("Return JSON with a description"), copy.getAcceptanceCriteria());
        original.setTaskSummary(" ");
        assertThrows(IllegalArgumentException.class, () -> OfflineTextReview.snapshot(original));
    }
}
