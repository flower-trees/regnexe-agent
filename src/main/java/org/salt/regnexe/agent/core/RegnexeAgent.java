/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.salt.regnexe.agent.core;

import lombok.extern.slf4j.Slf4j;
import org.salt.function.flow.FlowEngine;
import org.salt.function.flow.FlowInstance;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityType;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.AgentEvent;
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.agent.core.event.LoopSpans;
import org.salt.regnexe.agent.core.event.SpanIds;
import org.salt.regnexe.agent.core.llm.ModelProvider;
import org.salt.regnexe.agent.core.llm.ModelSpec;
import org.salt.regnexe.agent.core.marketplace.Marketplace;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.salt.regnexe.agent.core.task.ResultComposer;
import org.salt.regnexe.agent.core.task.state.RoundRecord;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.TaskRequest;
import org.salt.regnexe.agent.core.task.state.reflection.ReflectionDecision;
import org.salt.regnexe.agent.core.task.store.TaskStore;
import org.salt.regnexe.agent.core.task.worker.CapabilityExecutor;
import org.salt.regnexe.agent.core.task.worker.CapabilitySearcher;
import org.salt.regnexe.agent.core.task.worker.ContextBusKeys;
import org.salt.regnexe.agent.core.task.worker.Reflector;
import org.salt.regnexe.agent.core.task.worker.TaskPlanner;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.memory.AgentContext;
import org.salt.regnexe.agent.core.common.util.TextCompressor;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.memory.ConversationMemory;
import org.salt.jlangchain.core.history.memory.periodic.PeriodicConversationSummaryMemory;
import org.salt.jlangchain.core.history.memory.periodic.PeriodicConversationSummaryMemoryReader;
import org.salt.jlangchain.core.history.storage.ConversationStorage;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.MessageType;
import org.salt.regnexe.agent.core.task.worker.RequiredSkillPolicy;
import org.salt.jlangchain.rag.tools.Tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RegnexeAgent Runtime — orchestrates the Search→Plan→Execute→Reflect loop.
 * Not a Spring bean; obtain instances via {@link RegnexeAgentBuilder}.
 *
 * <p>Thread-safety note: one execute()/resume() call at a time per instance.
 * {@link #pause()} may be called from any thread while execute()/resume() is in progress.
 */
@Slf4j
public class RegnexeAgent {

    /**
     * Extra rounds granted on resume when the loaded task was already at its round ceiling
     * (TIMEOUT, or interrupted on its last allowed round) — otherwise it would immediately re-hit
     * the same ceiling and have zero chance to make progress with the resume's new instruction.
     */
    private static final int RESUME_ROUND_MARGIN = 3;

    private final FlowEngine flowEngine;
    private final ChainActor chainActor;
    private final CapabilitySearcher capabilitySearcher;
    private final TaskPlanner taskPlanner;
    private final CapabilityExecutor capabilityExecutor;
    private final Reflector reflector;
    private final ModelProvider llmProvider;
    private final ModelSpec defaultModel;
    /** Nullable — TaskPlanner falls back to defaultModel when unset. */
    private final ModelSpec plannerModel;
    /** Nullable — Reflector falls back to defaultModel when unset. */
    private final ModelSpec reflectorModel;
    private final Marketplace marketplace;
    private final TaskStore taskStore;
    private final ResultComposer resultComposer;
    private final int maxRounds;
    private final org.salt.regnexe.agent.core.task.state.ExecutionBudget executionBudget;
    private final AgentEventListener eventListener;
    private final ConversationStorage sessionStorage;
    private final int sessionBufferSize;
    private final int sessionCompactPeriod;
    private final AgentContext agentContext;
    private final int maxAgentIterations;
    private final int maxConsecutiveToolFailures;
    private final int maxContextOutputChars;
    private final boolean verbose;
    private final ConversationMemory sessionMemory;
    private final Path claudeCompatWorkspace;
    private final String projectMemory;
    private final java.util.Set<String> baseToolNames;

    /** Set at the start of each execute(); checked by pause(). */
    private volatile AtomicBoolean activeStopSignal;

    RegnexeAgent(FlowEngine flowEngine,
                ChainActor chainActor,
                CapabilitySearcher capabilitySearcher,
                TaskPlanner taskPlanner,
                CapabilityExecutor capabilityExecutor,
                Reflector reflector,
                ModelProvider llmProvider,
                ModelSpec defaultModel,
                ModelSpec plannerModel,
                ModelSpec reflectorModel,
                Marketplace marketplace,
                TaskStore taskStore,
                ResultComposer resultComposer,
                int maxRounds,
                AgentEventListener eventListener,
                ConversationStorage sessionStorage,
                int sessionBufferSize,
                int sessionCompactPeriod,
                AgentContext agentContext,
                int maxAgentIterations,
                int maxConsecutiveToolFailures,
                int maxContextOutputChars,
                boolean verbose,
                ConversationMemory sessionMemory,
                Path claudeCompatWorkspace,
                String projectMemory,
                java.util.Set<String> baseToolNames,
                org.salt.regnexe.agent.core.task.state.ExecutionBudget executionBudget) {
        this.executionBudget = executionBudget == null ? null : executionBudget.snapshot();
        this.flowEngine = flowEngine;
        this.chainActor = chainActor;
        this.capabilitySearcher = capabilitySearcher;
        this.taskPlanner = taskPlanner;
        this.capabilityExecutor = capabilityExecutor;
        this.reflector = reflector;
        this.llmProvider = llmProvider;
        this.defaultModel = defaultModel;
        this.plannerModel = plannerModel;
        this.reflectorModel = reflectorModel;
        this.marketplace = marketplace;
        this.taskStore = taskStore;
        this.resultComposer = resultComposer;
        this.maxRounds = maxRounds;
        this.eventListener = eventListener;
        this.sessionStorage = sessionStorage;
        this.sessionBufferSize = sessionBufferSize;
        this.sessionCompactPeriod = sessionCompactPeriod;
        this.agentContext = agentContext;
        this.maxAgentIterations = maxAgentIterations;
        this.maxConsecutiveToolFailures = maxConsecutiveToolFailures;
        this.maxContextOutputChars = maxContextOutputChars;
        this.verbose = verbose;
        this.sessionMemory = sessionMemory;
        this.claudeCompatWorkspace = claudeCompatWorkspace;
        this.projectMemory = projectMemory;
        this.baseToolNames = baseToolNames != null ? baseToolNames : java.util.Set.of();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public AgentResult execute(String goal) {
        TaskRequest request = new TaskRequest();
        request.setGoal(goal);
        return execute(request);
    }

    public AgentResult execute(TaskRequest request) {
        if (request.getExecutionBudget() == null && executionBudget != null) request.setExecutionBudget(executionBudget.snapshot());
        else if (request.getExecutionBudget() != null) request.setExecutionBudget(request.getExecutionBudget().snapshot());
        RequiredSkillPolicy.validate(request, marketplace);
        TaskExecutionState state = initState(request);
        taskStore.save(state);

        String runSpan = SpanIds.rootSpanId(state.getTaskId());
        eventListener.dispatch(AgentEvent.of(state.getTaskId(), 0, EventType.AGENT_STARTED,
                "Goal: " + request.getGoal() + " | maxRounds: " + maxRounds).inSpan(runSpan, null));

        List<HistoryInfos> sessionHistory = loadSessionHistory(state.getSessionId());
        return runLoop(state, sessionHistory, runSpan);
    }

    /**
     * Resume the most recently updated unfinished task for the given session (see
     * {@code TaskStore.listResumable}) — whatever it was doing (RUNNING, PAUSED, FAILED,
     * ESCALATED, or TIMEOUT), continuing with its full round/tool-call history intact via the
     * same {@code state.priorSteps}/{@code priorStepsSummary} carried across rounds during a
     * normal run. No resume-specific mode is threaded through Plan/Execute/Reflect — reloading
     * the persisted state and re-entering the same {@link #runLoop} is sufficient.
     *
     * @param supplementInput optional extra instruction, appended onto the task's original goal
     *                         so every phase's existing "Goal" prompt section picks it up
     *                         without any dedicated plumbing
     * @throws IllegalStateException if no resumable task exists for this session
     */
    public AgentResult resume(String sessionId, String supplementInput) {
        List<TaskExecutionState> resumable = taskStore.listResumable(sessionId);
        if (resumable.isEmpty()) {
            throw new IllegalStateException("No resumable task found for session: " + sessionId);
        }
        TaskExecutionState state = resumable.stream()
                .max(Comparator.comparingLong(TaskExecutionState::getUpdatedAt))
                .orElseThrow();

        return resumeTask(sessionId, state.getTaskId(), supplementInput);
    }

    /** Resume exactly the previewed task. Callers must also serialize session execution. */
    public AgentResult resumeTask(String sessionId, String taskId, String supplementInput) {
        TaskExecutionState state = taskStore.load(taskId)
                .orElseThrow(() -> new IllegalStateException("Task no longer exists: " + taskId));
        if (!java.util.Objects.equals(sessionId, state.getSessionId())) {
            throw new IllegalStateException("Task does not belong to session: " + sessionId);
        }
        if (state.getStatus() == TaskStatus.FINISHED) {
            throw new IllegalStateException("Task is already finished: " + taskId);
        }
        RequiredSkillPolicy.validate(state.getRequest(), marketplace);
        if (supplementInput != null && !supplementInput.isBlank()) {
            String goal = state.getRequest().getGoal();
            state.getRequest().setGoal((goal == null || goal.isBlank() ? "" : goal + "\n\n")
                    + "[Resumed with additional instruction]: " + supplementInput);
        }
        if (state.getRequest().getExecutionBudget() != null) {
            state.getRequest().getExecutionBudget().snapshot();
            org.salt.regnexe.agent.core.task.worker.BudgetedChatModel.rethrowLimit(state);
        }
        state.setTerminationReason(null);
        if (state.getCurrentRound() >= state.getMaxRounds()) {
            state.setMaxRounds(state.getMaxRounds() + RESUME_ROUND_MARGIN);
        }
        state.setStatus(TaskStatus.RUNNING);
        state.setUpdatedAt(System.currentTimeMillis());
        // Persist resumed progress before events, history loading, or model/tool calls.
        taskStore.save(state);

        // A resumed run is a child of the task's original root span, so it lands in the same trace.
        String runSpan = SpanIds.next();
        eventListener.dispatch(AgentEvent.of(state.getTaskId(), state.getCurrentRound(),
                EventType.AGENT_STARTED,
                "Resuming | rounds done: " + state.getCurrentRound()
                + (supplementInput != null && !supplementInput.isBlank()
                        ? " | supplement: " + supplementInput : ""))
                .inSpan(runSpan, SpanIds.rootSpanId(state.getTaskId())));

        List<HistoryInfos> sessionHistory = loadSessionHistory(state.getSessionId());
        return runLoop(state, sessionHistory, runSpan);
    }

    /**
     * Signal the currently-running McpAgentExecutor to stop. The task transitions to PAUSED and
     * can be resumed via {@link #resume}. Safe to call from any thread.
     */
    public void pause() {
        AtomicBoolean signal = this.activeStopSignal;
        if (signal != null) {
            signal.set(true);
        }
    }

    /** Read-only access to the marketplace, e.g. for a CLI to list/resolve capabilities by name. */
    public Marketplace getMarketplace() {
        return marketplace;
    }

    /**
     * Execute one explicitly selected Skill through the normal persisted task loop.
     * The signature and display goal remain compatible with slash-command callers.
     */
    public AgentResult executeSkill(String capabilityId, String args, String sessionId, String displayGoal) {
        if (capabilityId == null || capabilityId.isBlank()) {
            throw new IllegalArgumentException("Unknown skill: " + capabilityId);
        }
        TaskRequest request = new TaskRequest();
        request.setGoal(args == null ? "" : args);
        request.setRequiredSkillId(capabilityId);
        request.setSessionId(sessionId);
        request.setDisplayGoal(displayGoal != null && !displayGoal.isBlank()
                ? displayGoal : ("/" + capabilityId + " " + (args == null ? "" : args)).trim());
        return execute(request);
    }

    // ── Loop ─────────────────────────────────────────────────────────────────

    private AgentResult runLoop(TaskExecutionState state, List<HistoryInfos> sessionHistory, String runSpan) {
        try (var budgetScope = org.salt.regnexe.agent.core.task.worker.BudgetedChatModel.scope(state)) {
            AtomicBoolean stopSignal = new AtomicBoolean(false);
            this.activeStopSignal = stopSignal;

            Map<String, Object> transmitMap = buildTransmitMap(state, stopSignal, sessionHistory);
            transmitMap.put(ContextBusKeys.RUN_SPAN_ID, runSpan);

            FlowInstance flowInstance = flowEngine.builder()
                    .loop(
                            i -> state.getStatus() == TaskStatus.RUNNING && !stopSignal.get()
                                    && state.getCurrentRound() < state.getMaxRounds(),
                            capabilitySearcher, taskPlanner, capabilityExecutor, reflector
                    )
                    .build();

            try {
                flowEngine.execute(flowInstance, state.getRequest(), transmitMap);
            } catch (Exception e) {
                if (stopSignal.get() || Thread.currentThread().isInterrupted()) {
                    // Cancellation is a resumable pause, including cancellation during planning/network I/O.
                    Thread.interrupted();
                    state.setStatus(TaskStatus.PAUSED);
                    state.setTerminationReason("USER_INTERRUPTED");
                    taskStore.save(state);
                } else if ("MODEL_CALL_LIMIT".equals(state.getTerminationReason())) {
                    state.setStatus(TaskStatus.TIMEOUT);
                    taskStore.save(state);
                } else {
                    state.setTerminationReason("FRAMEWORK_ERROR");
                    log.error("RegnexeAgent loop failed: {}", e.getMessage(), e);
                    state.setStatus(TaskStatus.FAILED);
                    taskStore.save(state);
                    eventListener.dispatch(AgentEvent.of(state.getTaskId(), state.getCurrentRound(),
                            EventType.AGENT_COMPLETED,
                            "Status: " + state.getStatus() + " | " + e.getMessage()).inSpan(runSpan, null));
                    this.activeStopSignal = null;
                    throw e;
                }
            }
            if (stopSignal.get()) {
                Thread.interrupted();
                state.setStatus(TaskStatus.PAUSED);
                state.setTerminationReason("USER_INTERRUPTED");
                taskStore.save(state);
            }

            if (state.getStatus() == TaskStatus.RUNNING) {
                state.setStatus(TaskStatus.TIMEOUT);
                state.setTerminationReason("ROUND_LIMIT");
                taskStore.save(state);
            }

            this.activeStopSignal = null;

            List<RoundRecord> rounds = state.getRounds();
            ReflectionDecision lastDecision = (rounds == null || rounds.isEmpty())
                    ? null
                    : rounds.get(rounds.size() - 1).getReflection();
            String finalText = state.getStatus() == TaskStatus.PAUSED
                    ? (state.getLastToolResult() != null ? state.getLastToolResult() : "Task interrupted by user.")
                    : resultComposer.compose(state, lastDecision);

            // FINISHED/ESCALATED were the only statuses that used to leave a conversation_turns
            // trace — FAILED and TIMEOUT are just as settled (the loop won't advance this task any
            // further on its own) and deserve the same visibility in /history, not silence. A task
            // still RUNNING here (interrupted by a hard kill, not caught by any of this method's own
            // exception handling) is deliberately excluded — it's not settled, and /resume's
            // listResumable() already surfaces it with real round-by-round detail; /history isn't
            // the right place to guess at an unresolved task's content.
            if (state.getStatus() == TaskStatus.FINISHED || state.getStatus() == TaskStatus.ESCALATED
                    || state.getStatus() == TaskStatus.FAILED || state.getStatus() == TaskStatus.TIMEOUT) {
                String displayGoal = state.getRequest().getDisplayGoal();
                String humanTurn = (displayGoal != null && !displayGoal.isBlank())
                        ? displayGoal : state.getRequest().getGoal();
                try {
                    storeSessionRound(state.getSessionId(), humanTurn, finalText);
                } catch (org.salt.regnexe.agent.core.task.worker.BudgetedChatModel.LimitExceeded e) {
                    state.setStatus(TaskStatus.TIMEOUT);
                }
            }

            eventListener.dispatch(AgentEvent.of(state.getTaskId(), state.getCurrentRound(),
                    EventType.AGENT_COMPLETED,
                    "Status: " + state.getStatus() + " | Rounds: " + state.getCurrentRound()
                    + " | Reason: " + state.getTerminationReason() + " | Model attempts: " + state.getModelCallAttempts()).inSpan(runSpan, null));

            taskStore.save(state);
            return AgentResult.builder()
                    .taskId(state.getTaskId())
                    .status(state.getStatus())
                    .finalText(finalText)
                    .state(state)
                    .build();
        }
    }

    private Map<String, Object> buildTransmitMap(TaskExecutionState state,
                                                  AtomicBoolean stopSignal,
                                                  List<HistoryInfos> sessionHistory) {
        Map<String, Object> map = new HashMap<>();
        map.put(ContextBusKeys.STATE, state);
        map.put(ContextBusKeys.CHAIN_ACTOR, chainActor);
        map.put(ContextBusKeys.EVENT_LISTENER, eventListener);
        map.put(ContextBusKeys.STOP_SIGNAL, stopSignal);
        map.put(ContextBusKeys.TASK_STORE, taskStore);
        map.put(ContextBusKeys.AGENT_CONTEXT, agentContext);
        if (llmProvider != null) {
            map.put(ContextBusKeys.LLM_PROVIDER, llmProvider);
        }
        if (defaultModel != null) {
            map.put(ContextBusKeys.DEFAULT_MODEL, defaultModel);
            if (plannerModel != null) map.put(ContextBusKeys.PLANNER_MODEL, plannerModel);
            if (reflectorModel != null) map.put(ContextBusKeys.REFLECTOR_MODEL, reflectorModel);
        }
        if (marketplace != null) {
            map.put(ContextBusKeys.MARKETPLACE, marketplace);
        }
        if (sessionHistory != null && !sessionHistory.isEmpty()) {
            map.put(ContextBusKeys.SESSION_HISTORY, sessionHistory);
        }
        map.put(ContextBusKeys.MAX_AGENT_ITERATIONS, maxAgentIterations);
        // Untouched baseline for TaskPlanner to compute each round's override against — see its
        // own javadoc for why MAX_AGENT_ITERATIONS itself (mutated per round) isn't safe to reuse.
        map.put(ContextBusKeys.MAX_AGENT_ITERATIONS_DEFAULT, maxAgentIterations);
        map.put(ContextBusKeys.MAX_CONSECUTIVE_TOOL_FAILURES, maxConsecutiveToolFailures);
        map.put(ContextBusKeys.MAX_CONTEXT_OUTPUT_CHARS, maxContextOutputChars);
        map.put(ContextBusKeys.VERBOSE, verbose);
        if (claudeCompatWorkspace != null) {
            map.put(ContextBusKeys.CLAUDE_COMPAT_WORKSPACE, claudeCompatWorkspace);
        }
        if (projectMemory != null && !projectMemory.isBlank()) {
            map.put(ContextBusKeys.PROJECT_MEMORY, projectMemory);
        }
        if (!baseToolNames.isEmpty()) {
            map.put(ContextBusKeys.BASE_TOOL_NAMES, baseToolNames);
        }
        return map;
    }

    // ── State init ───────────────────────────────────────────────────────────

    private TaskExecutionState initState(TaskRequest request) {
        TaskExecutionState state = new TaskExecutionState();
        state.setTaskId(UUID.randomUUID().toString());
        state.setSessionId(request.getSessionId() != null
                ? request.getSessionId() : UUID.randomUUID().toString());
        state.setRequest(request);
        state.setMaxRounds(maxRounds);
        state.setCreatedAt(System.currentTimeMillis());
        state.setStatus(TaskStatus.RUNNING);
        state.setCurrentRound(0);
        state.setUpdatedAt(System.currentTimeMillis());
        state.setRounds(new ArrayList<>());
        return state;
    }

    // ── Session memory helpers ───────────────────────────────────────────────

    private List<HistoryInfos> loadSessionHistory(String sessionId) {
        List<HistoryInfos> history;
        if (sessionMemory != null) {
            history = sessionMemory.readHistory();
        } else if (sessionStorage != null) {
            history = PeriodicConversationSummaryMemoryReader.builder()
                    .appId("0").userId("0").sessionId(sessionId).storage(sessionStorage).build()
                    .readHistory();
        } else {
            return null;
        }
        return (history == null || history.isEmpty()) ? null : history;
    }

    private void storeSessionRound(String sessionId, String goal, String answer) {
        if (answer == null || answer.isBlank()) return;
        int sessionCap = Math.max(100, maxContextOutputChars / 2);
        String storedAnswer = answer.length() > sessionCap
                ? compressForSession(answer, sessionCap)
                : answer;
        ConversationMemory memory = sessionMemory;
        if (memory == null) {
            if (sessionStorage == null) return;
            if (defaultModel == null) {
                log.debug("Session storer skipped: no defaultModel configured for summary LLM");
                return;
            }
            // Periodic (batch) compaction by default: compresses a whole batch of rounds at
            // once when the buffer fills, instead of one round every time it overflows — far
            // fewer summarization LLM calls on long sessions than the old rolling strategy
            // (ConversationSummaryBufferMemory, still available via explicit withSessionMemory).
            memory = PeriodicConversationSummaryMemory.builder()
                    .appId("0").userId("0").sessionId(sessionId)
                    .maxSize(sessionCompactPeriod)
                    .storage(sessionStorage)
                    .llm(org.salt.regnexe.agent.core.task.worker.BudgetedChatModel.wrap(llmProvider.provide(defaultModel),
                            org.salt.regnexe.agent.core.task.worker.BudgetedChatModel.currentTask(), "summary"))
                    .build();
        }
        HistoryInfos turn = HistoryInfos.builder()
                .type(HistoryInfos.Type.NORMAL)
                .messages(List.of(
                        BaseMessage.fromMessage(MessageType.HUMAN.getCode(), goal),
                        BaseMessage.fromMessage(MessageType.AI.getCode(), storedAnswer)
                ))
                .build();
        memory.storeHistory(turn);
    }

    private String compressForSession(String text, int targetChars) {
        BaseChatModel llm = org.salt.regnexe.agent.core.task.worker.BudgetedChatModel.wrap(llmProvider.provide(defaultModel),
                org.salt.regnexe.agent.core.task.worker.BudgetedChatModel.currentTask(), "summary");
        return TextCompressor.compress(text, targetChars, chainActor, llm);
    }
}
