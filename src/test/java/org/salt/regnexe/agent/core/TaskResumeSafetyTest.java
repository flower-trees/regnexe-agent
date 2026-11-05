package org.salt.regnexe.agent.core;

import org.junit.Test;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.TaskRequest;
import org.salt.regnexe.agent.core.task.store.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** No model calls: stop at the first execution event, after revision validation/claim. */
public class TaskResumeSafetyTest {
    private static class Claimed extends RuntimeException {}

    private RegnexeAgent agent(TaskStore store, AgentEventListener listener) throws Exception {
        var constructor = RegnexeAgent.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Class<?>[] types = constructor.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class) args[i] = 1;
            else if (types[i] == boolean.class) args[i] = false;
            else if (types[i] == TaskStore.class) args[i] = store;
            else if (types[i] == AgentEventListener.class) args[i] = listener;
        }
        return (RegnexeAgent) constructor.newInstance(args);
    }

    private TaskExecutionState task(String id, long updatedAt) {
        var state = new TaskExecutionState();
        state.setTaskId(id); state.setSessionId("session"); state.setStatus(TaskStatus.PAUSED);
        state.setUpdatedAt(updatedAt); state.setMaxRounds(3);
        var request = new TaskRequest(); request.setGoal("goal " + id); state.setRequest(request);
        return state;
    }

    @Test public void resumesPreviewedTaskEvenWhenAnotherTaskIsNewer() throws Exception {
        var store = new InMemoryTaskStore();
        var previewed = task("previewed", 1); store.save(previewed);
        store.save(task("newer", 100));
        AtomicReference<String> observed = new AtomicReference<>();
        var agent = agent(store, event -> { observed.set(event.getTaskId()); throw new Claimed(); });
        assertThrows(Claimed.class, () -> agent.resumeTask("session", "previewed", 1, "extra"));
        assertEquals("previewed", observed.get());
        var saved = store.load("previewed").orElseThrow();
        assertEquals(2, saved.getVersion());
        assertEquals(TaskStatus.RUNNING, saved.getStatus());
        assertTrue(saved.getRequest().getGoal().contains("extra"));
        assertEquals(TaskStatus.PAUSED, store.load("newer").orElseThrow().getStatus());
    }

    @Test public void rejectsChangedFinishedForeignOrMissingTaskBeforeExecution() throws Exception {
        var store = new InMemoryTaskStore();
        var state = task("task", 1); store.save(state);
        var agent = agent(store, event -> { throw new AssertionError("Must not execute"); });
        assertThrows(TaskConflictException.class, () -> agent.resumeTask("session", "task", 0, "extra"));
        assertThrows(IllegalStateException.class, () -> agent.resumeTask("other", "task", 1, "extra"));
        assertThrows(IllegalStateException.class, () -> agent.resumeTask("session", "missing", 0, "extra"));
        state.setStatus(TaskStatus.FINISHED); store.save(state);
        assertThrows(IllegalStateException.class, () -> agent.resumeTask("session", "task", 2, "extra"));
        assertEquals("goal task", store.load("task").orElseThrow().getRequest().getGoal());
    }

    @Test public void saveConflictStopsResumeBeforeEvents() throws Exception {
        var backing = new InMemoryTaskStore(); backing.save(task("task", 1));
        TaskStore racing = new TaskStore() {
            public java.util.Optional<TaskExecutionState> load(String id) { return backing.load(id); }
            public java.util.List<TaskExecutionState> listResumable(String id) { return backing.listResumable(id); }
            public void markFinished(String id) {}
            public void save(TaskExecutionState state) { throw new TaskConflictException(state.getTaskId()); }
        };
        var agent = agent(racing, event -> { throw new AssertionError("Must not execute"); });
        assertThrows(TaskConflictException.class, () -> agent.resumeTask("session", "task", 1, "extra"));
        assertEquals(TaskStatus.PAUSED, backing.load("task").orElseThrow().getStatus());
    }

    @Test public void staleInMemorySnapshotCannotOverwriteWinner() {
        var store = new InMemoryTaskStore(); store.save(task("task", 1));
        var first = store.load("task").orElseThrow();
        var stale = store.load("task").orElseThrow();
        first.setStatus(TaskStatus.FINISHED); store.save(first);
        stale.setStatus(TaskStatus.RUNNING);
        assertThrows(TaskConflictException.class, () -> store.save(stale));
        assertEquals(TaskStatus.FINISHED, store.load("task").orElseThrow().getStatus());
    }
    @Test public void nestedStepsAreDetachedAndMessagesKeepTheirTypes() {
        var store = new InMemoryTaskStore();
        var state = task("task", 1);
        var round = new org.salt.regnexe.agent.core.task.state.RoundRecord();
        state.setRounds(new java.util.ArrayList<>(java.util.List.of(round)));
        state.setPriorSteps(java.util.List.of(org.salt.jlangchain.core.agent.memory.AgentStep.ofFunctionCall(
                org.salt.jlangchain.core.message.AIMessage.builder().content("check").build(),
                java.util.List.of(org.salt.jlangchain.core.message.ToolMessage.builder()
                        .name("read").toolCallId("call").content("ok").build()))));
        store.save(state);
        var loaded = store.load("task").orElseThrow();
        assertNotSame(state.getRounds().get(0), loaded.getRounds().get(0));
        assertEquals("check", loaded.getPriorSteps().get(0).getAiMessage().getContent());
        assertTrue(loaded.getPriorSteps().get(0).getToolResults().get(0)
                instanceof org.salt.jlangchain.core.message.ToolMessage);
        assertTrue(new TaskConflictException("task") instanceof org.salt.jlangchain.core.agent.AgentException);
    }

}
