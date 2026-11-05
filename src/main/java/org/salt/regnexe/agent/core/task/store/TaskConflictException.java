package org.salt.regnexe.agent.core.task.store;

/** A stale task snapshot must never overwrite newer persisted progress. */
public class TaskConflictException extends org.salt.jlangchain.core.agent.AgentException {
    public TaskConflictException(String taskId) {
        super("Task changed since it was loaded: " + taskId + "; reload before continuing");
    }
    /** Flow adapters may wrap signals; never retry or persist after a revision conflict. */
    public static void rethrowIfPresent(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TaskConflictException conflict) throw conflict;
        }
    }
}

