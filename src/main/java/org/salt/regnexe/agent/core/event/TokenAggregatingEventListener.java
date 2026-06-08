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

    // taskId -> (modelKey -> accumulated AiTokenUsage)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, AiTokenUsage>> accumulator =
            new ConcurrentHashMap<>();

    public TokenAggregatingEventListener(AgentEventListener delegate) {
        this.delegate = delegate;
    }

    @Override
    public void onEvent(AgentEvent event) {
        switch (event.getType()) {
            case TOKEN_USAGE, CAPABILITY_TOKEN_USAGE -> {
                accumulate(event);
                delegate.onEvent(event);
            }
            case AGENT_COMPLETED -> {
                emitSummary(event.getTaskId(), event.getRound());
                delegate.onEvent(event);
            }
            default -> delegate.onEvent(event);
        }
    }

    private void accumulate(AgentEvent event) {
        AgentTokenUsageEvent usageEvent = event.getTokenUsage();
        if (usageEvent == null) return;
        AiTokenUsage delta = usageEvent.getDeltaUsage();
        if (delta == null) return;

        String provider = delta.getProvider() != null ? delta.getProvider() : "unknown";
        String model = delta.getModel() != null ? delta.getModel() : "unknown";
        String modelKey = provider + ":" + model;
        String taskId = event.getTaskId() != null ? event.getTaskId() : "";

        accumulator
                .computeIfAbsent(taskId, k -> new ConcurrentHashMap<>())
                .merge(modelKey, delta.copy(), (existing, incoming) -> {
                    existing.add(incoming);
                    return existing;
                });
    }

    private void emitSummary(String taskId, int round) {
        Map<String, AiTokenUsage> modelMap = accumulator.remove(taskId != null ? taskId : "");
        if (modelMap == null || modelMap.isEmpty()) return;

        AiTokenUsage total = AiTokenUsage.empty();
        modelMap.values().forEach(total::add);

        delegate.onEvent(AgentEvent.ofTaskTokenSummary(taskId, round, total, modelMap));
    }
}
