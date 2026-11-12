package org.salt.regnexe.agent.core.task.state;

import lombok.Data;

/** Optional evaluation limits. Zero means no additional cap, not zero permitted calls. */
@Data
public class ExecutionBudget {
    private int hardMaxIterations;
    private int maxModelCalls;
    private boolean retryExecution;

    public ExecutionBudget snapshot() {
        if (hardMaxIterations < 0 || maxModelCalls < 0) {
            throw new IllegalArgumentException("Budget caps cannot be negative");
        }
        ExecutionBudget copy = new ExecutionBudget();
        copy.hardMaxIterations = hardMaxIterations;
        copy.maxModelCalls = maxModelCalls;
        copy.retryExecution = retryExecution;
        return copy;
    }
}
