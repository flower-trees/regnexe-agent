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
import org.salt.jlangchain.core.skill.Skill;

import java.io.IOException;
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
 * {@link #pause()} may be called from any thread while execute/resume is in progress.
 */
@Slf4j
public class RegnexeAgent {

    private final FlowEngine flowEngine;
    private final ChainActor chainActor;
    private final CapabilitySearcher capabilitySearcher;
    private final TaskPlanner taskPlanner;
    private final CapabilityExecutor capabilityExecutor;
    private final Reflector reflector;
    private final ModelProvider llmProvider;
    private final ModelSpec defaultModel;
    private final Marketplace marketplace;
    private final TaskStore taskStore;
    private final ResultComposer resultComposer;
    private final int maxRounds;
    private final AgentEventListener eventListener;
    private final ConversationStorage sessionStorage;
    private final int sessionBufferSize;
    private final int sessionCompactPeriod;
    private final AgentContext agentContext;
    private final int maxAgentIterations;
    private final int maxContextOutputChars;
    private final boolean verbose;
    private final ConversationMemory sessionMemory;
    private final Path claudeCompatWorkspace;
    private final String projectMemory;
    private final java.util.Set<String> baseToolNames;

    /** Set at the start of each execute()/resume(); checked by pause(). */
    private volatile AtomicBoolean activeStopSignal;

    RegnexeAgent(FlowEngine flowEngine,
                ChainActor chainActor,
                CapabilitySearcher capabilitySearcher,
                TaskPlanner taskPlanner,
                CapabilityExecutor capabilityExecutor,
                Reflector reflector,
                ModelProvider llmProvider,
                ModelSpec defaultModel,
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
                int maxContextOutputChars,
                boolean verbose,
                ConversationMemory sessionMemory,
                Path claudeCompatWorkspace,
                String projectMemory,
                java.util.Set<String> baseToolNames) {
        this.flowEngine = flowEngine;
        this.chainActor = chainActor;
        this.capabilitySearcher = capabilitySearcher;
        this.taskPlanner = taskPlanner;
        this.capabilityExecutor = capabilityExecutor;
        this.reflector = reflector;
        this.llmProvider = llmProvider;
        this.defaultModel = defaultModel;
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
        TaskExecutionState state = initState(request);
        taskStore.save(state);

        eventListener.dispatch(AgentEvent.of(state.getTaskId(), 0, EventType.AGENT_STARTED,
                "Goal: " + request.getGoal() + " | maxRounds: " + maxRounds));

        List<HistoryInfos> sessionHistory = loadSessionHistory(state.getSessionId());
        return runLoop(state, sessionHistory, false);
    }

    /**
     * Resume the most recently paused task for the given session.
     * The supplement input is appended to the original request so Planner can see
     * both the original goal and the new context in separate prompt sections.
     */
    public AgentResult resume(String sessionId, String supplementInput) {
        List<TaskExecutionState> resumable = taskStore.listResumable(sessionId);
        if (resumable.isEmpty()) {
            throw new IllegalStateException("No resumable task found for session: " + sessionId);
        }
        TaskExecutionState state = resumable.stream()
                .max(Comparator.comparingLong(TaskExecutionState::getUpdatedAt))
                .orElseThrow();

        state.setStatus(TaskStatus.RUNNING);
        if (supplementInput != null && !supplementInput.isBlank()) {
            state.getRequest().setSupplementInput(supplementInput);
        }

        eventListener.dispatch(AgentEvent.of(state.getTaskId(), state.getCurrentRound(),
                EventType.AGENT_STARTED,
                "Resuming | rounds done: " + state.getCurrentRound()
                + (supplementInput != null ? " | supplement: " + supplementInput : "")));

        List<HistoryInfos> sessionHistory = loadSessionHistory(state.getSessionId());
        return runLoop(state, sessionHistory, true);
    }

    /**
     * Signal the currently-running McpAgentExecutor to stop.
     * The task transitions to PAUSED and can be resumed via {@link #resume}.
     * Safe to call from any thread.
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
     * Directly invoke a SKILL capability by id, bypassing Search/Plan/Reflect.
     * Used for explicit user-triggered invocation (e.g. CLI "/skill-name args"), as opposed to
     * the planner autonomously selecting the skill during {@link #execute}.
     *
     * <p>Does not create a {@link TaskExecutionState} or touch {@code taskStore} — a skill run
     * cannot be paused/resumed via {@link #pause()}/{@link #resume}. Events still flow through
     * the same {@link #eventListener} used by execute()/resume(), so a caller's event rendering
     * (including the {@code TASK_TOKEN_SUMMARY} that {@code TokenAggregatingEventListener} emits
     * before {@code AGENT_COMPLETED}) needs no special-casing.
     *
     * @param capabilityId capability id as registered in the marketplace (format:
     *                      {@code <pluginId>.<skillName>})
     * @param args          raw argument text passed to the skill's internal executor as the user turn
     * @param sessionId     session to store the resulting turn under; null skips session-history storage
     * @param displayGoal   human-readable form for session history (e.g. "/review src/Foo.java");
     *                      falls back to "/<capabilityId> <args>" when null/blank
     */
    public AgentResult executeSkill(String capabilityId, String args, String sessionId, String displayGoal) {
        CapabilityDescriptor cap = marketplace.resolveDescriptor(capabilityId);
        if (cap == null) {
            throw new IllegalArgumentException("Unknown skill: " + capabilityId);
        }
        if (cap.getType() != CapabilityType.SKILL || cap.getSkillConfig() == null) {
            throw new IllegalArgumentException("Not a skill capability: " + capabilityId);
        }

        String taskId = UUID.randomUUID().toString();
        BaseChatModel llm = llmProvider.provide(defaultModel);

        eventListener.dispatch(AgentEvent.of(taskId, 0, EventType.AGENT_STARTED,
                "Skill: " + capabilityId + " | args: " + args));

        Skill.Builder skillBuilder = Skill.from(cap.getSkillConfig(), chainActor).llm(llm);
        if (claudeCompatWorkspace != null) {
            skillBuilder.claudeCompatWorkspace(claudeCompatWorkspace);
        }
        if (verbose) {
            skillBuilder.verbose(true);
        } else {
            String scope = "[skill:" + cap.getName() + "]";
            skillBuilder.onLlm(text -> eventListener.dispatch(
                    AgentEvent.of(taskId, 0, EventType.SKILL_LLM_RESPONDED, scope + " " + text)));
            skillBuilder.onToolCall(tc -> eventListener.dispatch(
                    AgentEvent.of(taskId, 0, EventType.TOOL_CALLED, scope + " " + tc)));
            skillBuilder.onObservation(obs -> eventListener.dispatch(
                    AgentEvent.of(taskId, 0, EventType.TOOL_RESULT, scope + " " + obs)));
        }
        skillBuilder.onTokenUsage(u -> eventListener.dispatch(
                AgentEvent.ofCapabilityTokenUsage(taskId, 0, cap.getName(), u)));

        String finalText;
        TaskStatus status;
        try {
            finalText = skillBuilder.build().invoke(args == null ? "" : args);
            status = TaskStatus.FINISHED;
        } catch (Exception e) {
            log.warn("executeSkill '{}' failed: {}", capabilityId, e.getMessage());
            finalText = "Skill execution failed: " + e.getMessage();
            status = TaskStatus.FAILED;
        }

        if (sessionId != null && status == TaskStatus.FINISHED) {
            String humanTurn = (displayGoal != null && !displayGoal.isBlank())
                    ? displayGoal : ("/" + capabilityId + " " + (args == null ? "" : args)).trim();
            storeSessionRound(sessionId, humanTurn, finalText);
        }

        eventListener.dispatch(AgentEvent.of(taskId, 0, EventType.AGENT_COMPLETED,
                "Status: " + status + " | Skill: " + capabilityId));

        return AgentResult.builder()
                .taskId(taskId)
                .status(status)
                .finalText(finalText)
                .build();
    }

    // ── Loop ─────────────────────────────────────────────────────────────────

    private AgentResult runLoop(TaskExecutionState state, List<HistoryInfos> sessionHistory, boolean resumeMode) {
        AtomicBoolean stopSignal = new AtomicBoolean(false);
        this.activeStopSignal = stopSignal;

        Map<String, Object> transmitMap = buildTransmitMap(state, stopSignal, sessionHistory, resumeMode);

        // Loop condition uses state.getCurrentRound() so resume continues correctly
        // from wherever the prior execution left off.
        FlowInstance flowInstance = flowEngine.builder()
                .loop(
                        i -> state.getStatus() == TaskStatus.RUNNING
                                && state.getCurrentRound() < state.getMaxRounds(),
                        capabilitySearcher, taskPlanner, capabilityExecutor, reflector
                )
                .build();

        try {
            flowEngine.execute(flowInstance, state.getRequest(), transmitMap);
        } catch (Exception e) {
            if (isTransientIOException(e)) {
                // A network blip (e.g. LLM call SocketTimeoutException) shouldn't strand
                // already-completed rounds in a dead FAILED state. PAUSED keeps the task
                // eligible for listResumable()/--resume so real work already done (files
                // already written to disk) isn't lost.
                log.warn("RegnexeAgent loop paused after transient I/O error: {}", e.getMessage());
                state.setStatus(TaskStatus.PAUSED);
                taskStore.save(state);
            } else {
                log.error("RegnexeAgent loop failed: {}", e.getMessage(), e);
                state.setStatus(TaskStatus.FAILED);
                taskStore.save(state);
                throw e;
            }
        }

        if (state.getStatus() == TaskStatus.RUNNING) {
            state.setStatus(TaskStatus.TIMEOUT);
            taskStore.save(state);
        }

        this.activeStopSignal = null;

        List<RoundRecord> rounds = state.getRounds();
        ReflectionDecision lastDecision = (rounds == null || rounds.isEmpty())
                ? null
                : rounds.get(rounds.size() - 1).getReflection();
        String finalText = resultComposer.compose(state, lastDecision);

        if (state.getStatus() == TaskStatus.FINISHED || state.getStatus() == TaskStatus.ESCALATED) {
            String displayGoal = state.getRequest().getDisplayGoal();
            String humanTurn = (displayGoal != null && !displayGoal.isBlank())
                    ? displayGoal : state.getRequest().getGoal();
            storeSessionRound(state.getSessionId(), humanTurn, finalText);
        }

        eventListener.dispatch(AgentEvent.of(state.getTaskId(), state.getCurrentRound(),
                EventType.AGENT_COMPLETED,
                "Status: " + state.getStatus() + " | Rounds: " + state.getCurrentRound()));

        return AgentResult.builder()
                .taskId(state.getTaskId())
                .status(state.getStatus())
                .finalText(finalText)
                .state(state)
                .build();
    }

    /** True if an IOException (e.g. SocketTimeoutException from an LLM call) appears anywhere in the cause chain. */
    private boolean isTransientIOException(Throwable e) {
        for (Throwable cur = e; cur != null; cur = cur.getCause()) {
            if (cur instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> buildTransmitMap(TaskExecutionState state,
                                                  AtomicBoolean stopSignal,
                                                  List<HistoryInfos> sessionHistory,
                                                  boolean resumeMode) {
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
        }
        if (marketplace != null) {
            map.put(ContextBusKeys.MARKETPLACE, marketplace);
        }
        if (sessionHistory != null && !sessionHistory.isEmpty()) {
            map.put(ContextBusKeys.SESSION_HISTORY, sessionHistory);
        }
        map.put(ContextBusKeys.RESUME_MODE, resumeMode);
        map.put(ContextBusKeys.MAX_AGENT_ITERATIONS, maxAgentIterations);
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
            long longId = (long) sessionId.hashCode();
            history = PeriodicConversationSummaryMemoryReader.builder()
                    .appId(0L).userId(0L).sessionId(longId).storage(sessionStorage).build()
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
            long longId = (long) sessionId.hashCode();
            // Periodic (batch) compaction by default: compresses a whole batch of rounds at
            // once when the buffer fills, instead of one round every time it overflows — far
            // fewer summarization LLM calls on long sessions than the old rolling strategy
            // (ConversationSummaryBufferMemory, still available via explicit withSessionMemory).
            memory = PeriodicConversationSummaryMemory.builder()
                    .appId(0L).userId(0L).sessionId(longId)
                    .maxSize(sessionCompactPeriod)
                    .storage(sessionStorage)
                    .llm(llmProvider.provide(defaultModel))
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
        BaseChatModel llm = llmProvider.provide(defaultModel);
        return TextCompressor.compress(text, targetChars, chainActor, llm);
    }
}
