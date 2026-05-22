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

package org.salt.regnexeagent.core;

import lombok.extern.slf4j.Slf4j;
import org.salt.function.flow.FlowEngine;
import org.salt.function.flow.FlowInstance;
import org.salt.regnexeagent.core.common.enums.TaskStatus;
import org.salt.regnexeagent.core.event.AgentEvent;
import org.salt.regnexeagent.core.event.AgentEventListener;
import org.salt.regnexeagent.core.event.EventType;
import org.salt.regnexeagent.core.llm.ModelProvider;
import org.salt.regnexeagent.core.llm.ModelSpec;
import org.salt.regnexeagent.core.market.Marketplace;
import org.salt.regnexeagent.core.task.AgentResult;
import org.salt.regnexeagent.core.task.ResultComposer;
import org.salt.regnexeagent.core.task.state.RoundRecord;
import org.salt.regnexeagent.core.task.state.TaskExecutionState;
import org.salt.regnexeagent.core.task.state.TaskRequest;
import org.salt.regnexeagent.core.task.state.reflection.ReflectionDecision;
import org.salt.regnexeagent.core.task.store.TaskStore;
import org.salt.regnexeagent.core.task.worker.CapabilityExecutor;
import org.salt.regnexeagent.core.task.worker.CapabilitySearcher;
import org.salt.regnexeagent.core.task.worker.ContextBusKeys;
import org.salt.regnexeagent.core.task.worker.Reflector;
import org.salt.regnexeagent.core.task.worker.TaskPlanner;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.memory.AgentContext;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.memory.summarybuffer.ConversationSummaryBufferMemoryReader;
import org.salt.jlangchain.core.history.memory.summarybuffer.ConversationSummaryBufferMemoryStorer;
import org.salt.jlangchain.core.history.storage.ConversationStorage;
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
                AgentContext agentContext) {
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

        eventListener.onEvent(AgentEvent.of(state.getTaskId(), 0, EventType.AGENT_STARTED,
                "Goal: " + request.getGoal() + " | maxRounds: " + maxRounds));

        String sessionSummary = loadSessionSummary(state.getSessionId());
        return runLoop(state, sessionSummary);
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

        eventListener.onEvent(AgentEvent.of(state.getTaskId(), state.getCurrentRound(),
                EventType.AGENT_STARTED,
                "Resuming | rounds done: " + state.getCurrentRound()
                + (supplementInput != null ? " | supplement: " + supplementInput : "")));

        String sessionSummary = loadSessionSummary(state.getSessionId());
        return runLoop(state, sessionSummary);
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

    private AgentResult runLoop(TaskExecutionState state, String sessionSummary) {
        AtomicBoolean stopSignal = new AtomicBoolean(false);
        this.activeStopSignal = stopSignal;

        Map<String, Object> transmitMap = buildTransmitMap(state, stopSignal, sessionSummary);

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
            storeSessionRound(state.getSessionId(), state.getRequest().getGoal(), finalText);
        }

        eventListener.onEvent(AgentEvent.of(state.getTaskId(), state.getCurrentRound(),
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
                                                  String sessionSummary) {
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
        if (sessionSummary != null) {
            map.put(ContextBusKeys.SESSION_SUMMARY, sessionSummary);
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

    private String loadSessionSummary(String sessionId) {
        if (sessionStorage == null) return null;
        long longId = (long) sessionId.hashCode();
        ConversationSummaryBufferMemoryReader reader = ConversationSummaryBufferMemoryReader.builder()
                .appId(0L).userId(0L).sessionId(longId).storage(sessionStorage).build();
        List<HistoryInfos> history = reader.readHistory();
        if (history == null || history.isEmpty()) return null;
        return formatHistory(history);
    }

    private String formatHistory(List<HistoryInfos> history) {
        StringBuilder sb = new StringBuilder();
        for (HistoryInfos h : history) {
            if (h.getType() == HistoryInfos.Type.SUMMARY) {
                for (BaseMessage msg : h.getMessages()) {
                    sb.append(msg.getContent()).append("\n");
                }
            } else {
                for (BaseMessage msg : h.getMessages()) {
                    String role = MessageType.HUMAN.getCode().equals(msg.getRole())
                            ? "User" : "Assistant";
                    sb.append(role).append(": ").append(msg.getContent()).append("\n");
                }
                sb.append("\n");
            }
        }
        return sb.toString().trim();
    }

    private void storeSessionRound(String sessionId, String goal, String answer) {
        if (sessionStorage == null || answer == null || answer.isBlank()) return;
        if (defaultModel == null) {
            log.debug("Session storer skipped: no defaultModel configured for summary LLM");
            return;
        }
        long longId = (long) sessionId.hashCode();
        ConversationSummaryBufferMemoryStorer storer = ConversationSummaryBufferMemoryStorer.builder()
                .appId(0L).userId(0L).sessionId(longId)
                .maxSize(sessionBufferSize)
                .storage(sessionStorage)
                .llm(llmProvider.provide(defaultModel))
                .build();
        HistoryInfos turn = HistoryInfos.builder()
                .type(HistoryInfos.Type.NORMAL)
                .messages(List.of(
                        BaseMessage.fromMessage(MessageType.HUMAN.getCode(), goal),
                        BaseMessage.fromMessage(MessageType.AI.getCode(), answer)
                ))
                .build();
        storer.storeHistory(turn);
    }
}
