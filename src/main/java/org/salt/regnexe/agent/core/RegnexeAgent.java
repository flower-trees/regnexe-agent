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
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.AgentEvent;
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.agent.core.llm.ModelProvider;
import org.salt.regnexe.agent.core.llm.ModelSpec;
import org.salt.regnexe.agent.core.market.Marketplace;
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
import org.salt.jlangchain.core.history.memory.summarybuffer.ConversationSummaryBufferMemory;
import org.salt.jlangchain.core.history.memory.summarybuffer.ConversationSummaryBufferMemoryReader;
import org.salt.jlangchain.core.history.storage.ConversationStorage;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.MessageType;

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
    private final AgentContext agentContext;
    private final int maxAgentIterations;
    private final int maxContextOutputChars;
    private final boolean verbose;
    private final ConversationMemory sessionMemory;

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
                AgentContext agentContext,
                int maxAgentIterations,
                int maxContextOutputChars,
                boolean verbose,
                ConversationMemory sessionMemory) {
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
        this.agentContext = agentContext;
        this.maxAgentIterations = maxAgentIterations;
        this.maxContextOutputChars = maxContextOutputChars;
        this.verbose = verbose;
        this.sessionMemory = sessionMemory;
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
        return runLoop(state, sessionHistory);
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
        return runLoop(state, sessionHistory);
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

    // ── Loop ─────────────────────────────────────────────────────────────────

    private AgentResult runLoop(TaskExecutionState state, List<HistoryInfos> sessionHistory) {
        AtomicBoolean stopSignal = new AtomicBoolean(false);
        this.activeStopSignal = stopSignal;

        Map<String, Object> transmitMap = buildTransmitMap(state, stopSignal, sessionHistory);

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
            log.error("RegnexeAgent loop failed: {}", e.getMessage(), e);
            state.setStatus(TaskStatus.FAILED);
            taskStore.save(state);
            throw e;
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
        }
        if (marketplace != null) {
            map.put(ContextBusKeys.MARKETPLACE, marketplace);
        }
        if (sessionHistory != null && !sessionHistory.isEmpty()) {
            map.put(ContextBusKeys.SESSION_HISTORY, sessionHistory);
        }
        map.put(ContextBusKeys.MAX_AGENT_ITERATIONS, maxAgentIterations);
        map.put(ContextBusKeys.MAX_CONTEXT_OUTPUT_CHARS, maxContextOutputChars);
        map.put(ContextBusKeys.VERBOSE, verbose);
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
            history = ConversationSummaryBufferMemoryReader.builder()
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
            memory = ConversationSummaryBufferMemory.builder()
                    .appId(0L).userId(0L).sessionId(longId)
                    .maxSize(sessionBufferSize)
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
