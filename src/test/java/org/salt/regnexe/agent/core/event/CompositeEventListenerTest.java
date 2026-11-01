package org.salt.regnexe.agent.core.event;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class CompositeEventListenerTest {

    private static AgentEvent event(EventType type) {
        return AgentEvent.of("task-1", 1, type, "text");
    }

    @Test
    public void everyListenerReceivesEveryEventInRegistrationOrder() {
        List<String> seen = new ArrayList<>();
        AgentEventListener composite = new CompositeEventListener(List.of(
                e -> seen.add("a:" + e.getType()),
                e -> seen.add("b:" + e.getType())));

        composite.dispatch(event(EventType.AGENT_STARTED));
        composite.dispatch(event(EventType.AGENT_COMPLETED));

        assertEquals(List.of("a:AGENT_STARTED", "b:AGENT_STARTED", "a:AGENT_COMPLETED", "b:AGENT_COMPLETED"), seen);
    }

    @Test
    public void eachListenerKeepsItsOwnFilter() {
        List<EventType> filtered = new ArrayList<>();
        List<EventType> unfiltered = new ArrayList<>();
        AgentEventListener onlyToolCalls = new AgentEventListener() {
            @Override
            public void onEvent(AgentEvent e) {
                filtered.add(e.getType());
            }

            @Override
            public boolean shouldHandle(EventType type) {
                return type == EventType.TOOL_CALLED;
            }
        };
        AgentEventListener composite = new CompositeEventListener(List.of(onlyToolCalls, e -> unfiltered.add(e.getType())));

        composite.dispatch(event(EventType.PLAN_STARTED));
        composite.dispatch(event(EventType.TOOL_CALLED));

        assertEquals(List.of(EventType.TOOL_CALLED), filtered);
        assertEquals(List.of(EventType.PLAN_STARTED, EventType.TOOL_CALLED), unfiltered);
    }

    @Test
    public void failingListenerDoesNotBlockLaterOnes() {
        List<EventType> seen = new ArrayList<>();
        AgentEventListener composite = new CompositeEventListener(List.of(
                e -> { throw new IllegalStateException("boom"); },
                e -> seen.add(e.getType())));

        composite.dispatch(event(EventType.TOOL_RESULT));

        assertEquals(List.of(EventType.TOOL_RESULT), seen);
    }
}
