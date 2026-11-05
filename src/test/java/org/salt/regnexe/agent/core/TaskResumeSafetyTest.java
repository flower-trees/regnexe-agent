package org.salt.regnexe.agent.core;

import org.junit.Test;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.TaskRequest;
import org.salt.regnexe.agent.core.task.store.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** No model calls: stop at the first execution event, after task validation/loading. */
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
        assertThrows(Claimed.class, () -> agent.resumeTask("session", "previewed", "extra"));
        assertEquals("previewed", observed.get());
        var saved = store.load("previewed").orElseThrow();
        assertEquals(TaskStatus.RUNNING, saved.getStatus());
        assertTrue(saved.getRequest().getGoal().contains("extra"));
        assertEquals(TaskStatus.PAUSED, store.load("newer").orElseThrow().getStatus());
    }

    @Test public void rejectsFinishedForeignOrMissingTaskBeforeExecution() throws Exception {
        var store = new InMemoryTaskStore();
        var state = task("task", 1); store.save(state);
        var agent = agent(store, event -> { throw new AssertionError("Must not execute"); });
        assertThrows(IllegalStateException.class, () -> agent.resumeTask("other", "task", "extra"));
        assertThrows(IllegalStateException.class, () -> agent.resumeTask("session", "missing", "extra"));
        state.setStatus(TaskStatus.FINISHED); store.save(state);
        assertThrows(IllegalStateException.class, () -> agent.resumeTask("session", "task", "extra"));
        assertEquals("goal task", store.load("task").orElseThrow().getRequest().getGoal());
    }

    @Test public void reloadsSelectedTaskAtExecutionTime() throws Exception {
        var store = new InMemoryTaskStore();
        store.save(task("task", 1));
        var updated = task("task", 2);
        updated.getRequest().setGoal("latest goal");
        store.save(updated);
        var agent = agent(store, event -> { throw new Claimed(); });
        assertThrows(Claimed.class, () -> agent.resumeTask("session", "task", "extra"));
        assertTrue(store.load("task").orElseThrow().getRequest().getGoal().startsWith("latest goal"));
    }
}
