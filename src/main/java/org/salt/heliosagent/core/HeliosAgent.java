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

package org.salt.heliosagent.core;

import lombok.extern.slf4j.Slf4j;
import org.salt.function.flow.FlowEngine;
import org.salt.function.flow.FlowInstance;
import org.salt.heliosagent.core.common.enums.TaskStatus;
import org.salt.heliosagent.core.event.AgentEvent;
import org.salt.heliosagent.core.event.AgentEventListener;
import org.salt.heliosagent.core.event.EventType;
import org.salt.heliosagent.core.llm.ModelProvider;
import org.salt.heliosagent.core.llm.ModelSpec;
import org.salt.heliosagent.core.market.Marketplace;
import org.salt.heliosagent.core.task.AgentResult;
import org.salt.heliosagent.core.task.ResultComposer;
import org.salt.heliosagent.core.task.state.RoundRecord;
import org.salt.heliosagent.core.task.state.TaskExecutionState;
import org.salt.heliosagent.core.task.state.TaskRequest;
import org.salt.heliosagent.core.task.state.reflection.ReflectionDecision;
import org.salt.heliosagent.core.task.store.TaskStore;
import org.salt.heliosagent.core.task.worker.CapabilityExecutor;
import org.salt.heliosagent.core.task.worker.CapabilitySearcher;
import org.salt.heliosagent.core.task.worker.ContextBusKeys;
import org.salt.heliosagent.core.task.worker.Reflector;
import org.salt.heliosagent.core.task.worker.TaskPlanner;
import org.salt.jlangchain.core.ChainActor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HeliosAgent Runtime — orchestrates the Search→Plan→Execute→Reflect loop.
 * Not a Spring bean; obtain instances via {@link HeliosAgentBuilder}.
 */
@Slf4j
public class HeliosAgent {

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

    HeliosAgent(FlowEngine flowEngine,
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
                AgentEventListener eventListener) {
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
    }

    public AgentResult execute(String goal) {
        TaskRequest request = new TaskRequest();
        request.setGoal(goal);
        return execute(request);
    }

    public AgentResult execute(TaskRequest request) {
        TaskExecutionState state = initState(request);

        if (taskStore != null) {
            taskStore.save(state);
        }

        eventListener.onEvent(AgentEvent.of(state.getTaskId(), 0, EventType.AGENT_STARTED,
                "Goal: " + request.getGoal() + " | maxRounds: " + maxRounds));

        Map<String, Object> transmitMap = new HashMap<>();
        transmitMap.put(ContextBusKeys.STATE, state);
        transmitMap.put(ContextBusKeys.CHAIN_ACTOR, chainActor);
        transmitMap.put(ContextBusKeys.EVENT_LISTENER, eventListener);
        if (llmProvider != null) {
            transmitMap.put(ContextBusKeys.LLM_PROVIDER, llmProvider);
        }
        if (defaultModel != null) {
            transmitMap.put(ContextBusKeys.DEFAULT_MODEL, defaultModel);
        }
        if (marketplace != null) {
            transmitMap.put(ContextBusKeys.MARKETPLACE, marketplace);
        }

        FlowInstance flowInstance = flowEngine.builder()
                .loop(
                        i -> state.getStatus() == TaskStatus.RUNNING && i < state.getMaxRounds(),
                        capabilitySearcher, taskPlanner, capabilityExecutor, reflector
                )
                .build();

        flowEngine.execute(flowInstance, request, transmitMap);

        if (state.getStatus() == TaskStatus.RUNNING) {
            state.setStatus(TaskStatus.TIMEOUT);
        }

        List<RoundRecord> rounds = state.getRounds();
        ReflectionDecision lastDecision = (rounds == null || rounds.isEmpty())
                ? null
                : rounds.get(rounds.size() - 1).getReflection();

        String finalText = resultComposer != null
                ? resultComposer.compose(state, lastDecision)
                : null;

        if (taskStore != null) {
            taskStore.markFinished(state.getTaskId());
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

    private TaskExecutionState initState(TaskRequest request) {
        TaskExecutionState state = new TaskExecutionState();
        state.setTaskId(UUID.randomUUID().toString());
        state.setSessionId(request.getSessionId() != null ? request.getSessionId() : UUID.randomUUID().toString());
        state.setRequest(request);
        state.setMaxRounds(maxRounds);
        state.setCreatedAt(System.currentTimeMillis());
        state.setStatus(TaskStatus.RUNNING);
        state.setCurrentRound(0);
        state.setUpdatedAt(System.currentTimeMillis());
        state.setRounds(new ArrayList<>());
        return state;
    }
}
