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

package org.salt.regnexe.agent.core.event;

import org.salt.jlangchain.ai.common.param.AiTokenUsage;
import org.salt.jlangchain.core.agent.AgentTokenUsageEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps another {@link AgentEventListener} and automatically accumulates token usage
 * across all rounds of a task. Emits a {@link EventType#TASK_TOKEN_SUMMARY} event
 * (broken down by model) just before each {@link EventType#AGENT_COMPLETED} event.
 *
 * <p>Applied automatically by {@link org.salt.regnexe.agent.core.RegnexeAgentBuilder}
 * so callers do not need to configure this manually.
 */
public class TokenAggregatingEventListener implements AgentEventListener {

    private final AgentEventListener delegate;

    // parentTaskId -> (modelKey -> accumulated AiTokenUsage)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, AiTokenUsage>> accumulator =
            new ConcurrentHashMap<>();

    // parentTaskId -> (subTaskId -> last seen cumulative toolCalls) — used to compute per-event delta
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> toolCallsTracker =
            new ConcurrentHashMap<>();

    // parentTaskId -> (modelKey -> accumulated LLM duration ms)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> llmDurationMs =
            new ConcurrentHashMap<>();

    // parentTaskId -> timestamp of first token event (ms)
    private final ConcurrentHashMap<String, Long> taskStartMs = new ConcurrentHashMap<>();

    public TokenAggregatingEventListener(AgentEventListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onEvent(AgentEvent event) {
        switch (event.getType()) {
            case TOKEN_USAGE, CAPABILITY_TOKEN_USAGE -> {
                accumulate(event);
                delegate.dispatch(event);
            }
            case AGENT_COMPLETED -> {
                emitSummary(event.getTaskId(), event.getRound(), event.getTimestamp());
                delegate.dispatch(event);
            }
            default -> delegate.dispatch(event);
        }
    }

    private void accumulate(AgentEvent event) {
        AgentTokenUsageEvent usageEvent = event.getTokenUsage();
        if (usageEvent == null) return;
        AiTokenUsage delta = usageEvent.getDeltaUsage();
        if (delta == null) return;

        String parentTaskId = event.getTaskId() != null ? event.getTaskId() : "";
        String subTaskId = usageEvent.getTaskId() != null ? usageEvent.getTaskId() : parentTaskId;
        String provider = delta.getProvider() != null ? delta.getProvider() : "unknown";
        String model = delta.getModel() != null ? delta.getModel() : "unknown";
        String modelKey = provider + ":" + model;

        // Compute tool_calls delta: event.toolCalls is cumulative per sub-task; delta_usage always has 0
        long currentToolCalls = usageEvent.getToolCalls();
        long prevToolCalls = toolCallsTracker
                .computeIfAbsent(parentTaskId, k -> new ConcurrentHashMap<>())
                .getOrDefault(subTaskId, 0L);
        toolCallsTracker.get(parentTaskId).put(subTaskId, currentToolCalls);

        AiTokenUsage effectiveDelta = delta.copy();
        effectiveDelta.setToolCalls(currentToolCalls - prevToolCalls);

        accumulator
                .computeIfAbsent(parentTaskId, k -> new ConcurrentHashMap<>())
                .merge(modelKey, effectiveDelta, (existing, incoming) -> {
                    existing.add(incoming);
                    return existing;
                });

        // Accumulate LLM inference duration
        llmDurationMs
                .computeIfAbsent(parentTaskId, k -> new ConcurrentHashMap<>())
                .merge(modelKey, usageEvent.getDeltaDurationMs(), Long::sum);

        // Record first event timestamp as task start
        taskStartMs.computeIfAbsent(parentTaskId, k -> event.getTimestamp());
    }

    private void emitSummary(String taskId, int round, long completedAt) {
        String key = taskId != null ? taskId : "";
        Map<String, AiTokenUsage> modelMap = accumulator.remove(key);
        toolCallsTracker.remove(key);
        Map<String, Long> durations = llmDurationMs.remove(key);
        Long startTime = taskStartMs.remove(key);

        if (modelMap == null || modelMap.isEmpty()) return;

        AiTokenUsage total = AiTokenUsage.empty();
        modelMap.values().forEach(total::add);
        total.setProvider(null);
        total.setModel(null);

        long elapsedMs = (startTime != null && completedAt > 0) ? (completedAt - startTime) : 0;
        long totalLlmMs = durations != null ? durations.values().stream().mapToLong(Long::longValue).sum() : 0;

        delegate.dispatch(AgentEvent.ofTaskTokenSummary(
                taskId, round, total, modelMap, elapsedMs, totalLlmMs,
                durations != null ? durations : Map.of()));
    }
}
