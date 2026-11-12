package org.salt.regnexe.agent.core.task.worker;

import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;

/** Adapters with internal re-requests must meter each underlying invocation, not just the outer call. */
public interface TaskBudgetAwareModel {
    BaseChatModel withTaskBudget(TaskExecutionState state, String phase);
}
