package org.salt.regnexe.agent.core.task.worker;

import java.util.LinkedHashMap;
import org.salt.jlangchain.ai.common.param.AiChatInput;
import org.salt.jlangchain.ai.chat.strategy.AiChatActuator;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.AIMessage;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;

/** Counts attempted synchronous model requests, including failures and parse retries. */
public final class BudgetedChatModel extends BaseChatModel {
    public static final class LimitExceeded extends RuntimeException {
        public LimitExceeded() { super("Task model call limit reached"); }
    }
    private static final ThreadLocal<TaskExecutionState> ACTIVE = new ThreadLocal<>();
    public static TaskExecutionState currentTask() { return ACTIVE.get(); }
    public static AutoCloseableScope scope(TaskExecutionState state) { return new AutoCloseableScope(state); }
    public static final class AutoCloseableScope implements AutoCloseable {
        private final TaskExecutionState prior = ACTIVE.get();
        private AutoCloseableScope(TaskExecutionState state) { ACTIVE.set(state); }
        @Override public void close() { if (prior == null) ACTIVE.remove(); else ACTIVE.set(prior); }
    }
    private final BaseChatModel delegate;
    private final TaskExecutionState state;
    private final String phase;
    private BudgetedChatModel(BaseChatModel delegate, TaskExecutionState state, String phase) {
        this.delegate = delegate; this.state = state; this.phase = phase;
        setVendor(delegate.getVendor()); setModel(delegate.getModel()); setTemperature(delegate.getTemperature());
        setModelKwargs(delegate.getModelKwargs()); setTools(delegate.getTools());
    }
    public static BaseChatModel wrap(BaseChatModel delegate, TaskExecutionState state, String phase) {
        if (state == null) return delegate;
        if (delegate instanceof TaskBudgetAwareModel aware) return aware.withTaskBudget(state, phase);
        return new BudgetedChatModel(delegate, state, phase);
    }
    public static void rethrowLimit(TaskExecutionState state) {
        if ("MODEL_CALL_LIMIT".equals(state.getTerminationReason())) throw new LimitExceeded();
    }
    @Override public AIMessage invoke(Object input) {
        synchronized (state) {
            rethrowLimit(state);
            if (state.getModelCallAttempts() == null) state.setModelCallAttempts(new LinkedHashMap<>());
            int used = state.getModelCallAttempts().values().stream().mapToInt(Integer::intValue).sum();
            var budget = state.getRequest().getExecutionBudget();
            if (budget != null && budget.getMaxModelCalls() > 0 && used >= budget.getMaxModelCalls()) {
                state.setTerminationReason("MODEL_CALL_LIMIT"); state.setStatus(TaskStatus.TIMEOUT);
                throw new LimitExceeded();
            }
            state.getModelCallAttempts().merge(phase, 1, Integer::sum);
        }
        return configured().invoke(withLimitNotice(input));
    }
    private Object withLimitNotice(Object input) {
        if (!"execute".equals(phase) || state.getRounds() == null || state.getRounds().isEmpty()) return input;
        var round = state.getRounds().get(state.getRounds().size() - 1);
        round.setExecutorModelCalls(round.getExecutorModelCalls() + 1);
        if (state.getRequest().getExecutionBudget() == null || round.getAppliedMaxIterations() == null) return input;
        int remaining = round.getAppliedMaxIterations() - round.getExecutorModelCalls() + 1;
        if (remaining < 1 || remaining > 2) return input;
        java.util.List<org.salt.jlangchain.core.message.BaseMessage> messages = new java.util.ArrayList<>();
        if (input instanceof org.salt.jlangchain.core.prompt.value.ChatPromptValue chat) messages.addAll(chat.getMessages());
        else messages.add(org.salt.jlangchain.core.message.HumanMessage.builder().content(
                input instanceof org.salt.jlangchain.core.prompt.value.StringPromptValue text
                        ? text.getText() : String.valueOf(input)).build());
        messages.add(org.salt.jlangchain.core.message.HumanMessage.builder().content(
                "[SYSTEM NOTICE] Only " + remaining + " model invocation(s), including this one, remain in this execution round. "
                + "Finish with the available evidence if sufficient; do not claim unperformed work.").build());
        return org.salt.jlangchain.core.prompt.value.ChatPromptValue.builder().messages(messages).build();
    }

    private BaseChatModel configured() {
        BaseChatModel copy = delegate.copy(); copy.setTools(getTools());
        copy.setTemperature(getTemperature()); copy.setModelKwargs(getModelKwargs());
        return copy;
    }
    @Override public void otherInformation(AiChatInput input) { delegate.otherInformation(input); }
    @Override public Class<? extends AiChatActuator> getActuator() { return delegate.getActuator(); }
    @Override public BaseChatModel copy() { return new BudgetedChatModel(configured(), state, phase); }
    @Override public BaseChatModel withJsonMode() { return new BudgetedChatModel(configured().withJsonMode(), state, phase); }
}
