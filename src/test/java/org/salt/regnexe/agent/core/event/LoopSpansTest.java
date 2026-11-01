package org.salt.regnexe.agent.core.event;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LoopSpansTest {

    private static AgentEvent event(EventType type) {
        return AgentEvent.of("task-1", 1, type, "text");
    }

    @Test
    public void taskIdsAreStableAndWellFormed() {
        assertEquals(SpanIds.traceId("task-1"), SpanIds.traceId("task-1"));
        assertEquals(SpanIds.rootSpanId("task-1"), SpanIds.rootSpanId("task-1"));
        assertNotEquals(SpanIds.traceId("task-1"), SpanIds.traceId("task-2"));
        assertTrue(SpanIds.traceId("task-1").matches("[0-9a-f]{32}"));
        assertTrue(SpanIds.rootSpanId("task-1").matches("[0-9a-f]{16}"));
        assertTrue(SpanIds.next().matches("[0-9a-f]{16}"));
        assertNotEquals(SpanIds.next(), SpanIds.next());
    }

    @Test
    public void followUpEventsShareTheSpanOfTheCallTheyBelongTo() {
        LoopSpans spans = new LoopSpans(() -> "phase");

        AgentEvent llm = spans.llmStarted(event(EventType.LLM_RESPONDED));
        AgentEvent usage = spans.llmFollowUp(event(EventType.TOKEN_USAGE));
        AgentEvent call = spans.toolCalled(event(EventType.TOOL_CALLED));
        AgentEvent result = spans.toolResult(event(EventType.TOOL_RESULT));

        assertEquals("phase", llm.getParentSpanId());
        assertEquals(llm.getSpanId(), usage.getSpanId());
        assertEquals("phase", call.getParentSpanId());
        assertEquals(call.getSpanId(), result.getSpanId());
        assertNotEquals(llm.getSpanId(), call.getSpanId());

        AgentEvent nextLlm = spans.llmStarted(event(EventType.LLM_RESPONDED));
        assertNotEquals(llm.getSpanId(), nextLlm.getSpanId());
    }

    @Test
    public void nestedLoopHangsUnderTheOuterToolCallRunningAtThatMoment() {
        LoopSpans outer = new LoopSpans(() -> "phase");
        LoopSpans subAgent = outer.nested();

        AgentEvent firstCall = outer.toolCalled(event(EventType.TOOL_CALLED));
        AgentEvent innerLlm = subAgent.llmStarted(event(EventType.AGENT_LLM_RESPONDED));
        AgentEvent innerTool = subAgent.toolCalled(event(EventType.TOOL_CALLED));
        outer.toolResult(event(EventType.TOOL_RESULT));

        assertEquals(firstCall.getSpanId(), innerLlm.getParentSpanId());
        assertEquals(firstCall.getSpanId(), innerTool.getParentSpanId());

        AgentEvent secondCall = outer.toolCalled(event(EventType.TOOL_CALLED));
        AgentEvent laterInnerLlm = subAgent.llmStarted(event(EventType.AGENT_LLM_RESPONDED));
        assertEquals(secondCall.getSpanId(), laterInnerLlm.getParentSpanId());
    }

    @Test
    public void usageWithoutAPrecedingLlmEventHasNoSpan() {
        LoopSpans spans = new LoopSpans(() -> "phase");
        assertNull(spans.llmFollowUp(event(EventType.CAPABILITY_TOKEN_USAGE)).getSpanId());
    }

    @Test
    public void spanFieldsDoNotDisturbTheRestOfTheEvent() {
        AgentEvent original = event(EventType.PLAN_STARTED);
        AgentEvent tagged = original.inSpan("s", "p");
        assertEquals(original.getTaskId(), tagged.getTaskId());
        assertEquals(original.getText(), tagged.getText());
        assertEquals(original.getTimestamp(), tagged.getTimestamp());
        assertNull(original.getSpanId());
    }
}
