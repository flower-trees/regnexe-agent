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

import com.fasterxml.jackson.databind.node.LongNode;
import lombok.Builder;
import lombok.Value;
import org.salt.jlangchain.ai.common.param.AiTokenUsage;
import org.salt.jlangchain.core.agent.AgentTokenUsageEvent;
import org.salt.jlangchain.utils.JsonUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single observable event emitted during agent execution.
 * {@code text} is human-readable and can be displayed as-is (CLI, SSE stream, etc.).
 * Use {@code type} to filter or route events.
 */
@Value
@Builder
public class AgentEvent {

    String taskId;
    int round;
    EventType type;
    String text;
    AgentTokenUsageEvent tokenUsage;
    long timestamp;

    public static AgentEvent of(String taskId, int round, EventType type, String text) {
        return AgentEvent.builder()
                .taskId(taskId)
                .round(round)
                .type(type)
                .text(text)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AgentEvent ofTokenUsage(String taskId, int round, AgentTokenUsageEvent usage) {
        return AgentEvent.builder()
                .taskId(taskId)
                .round(round)
                .type(EventType.TOKEN_USAGE)
                .tokenUsage(usage)
                .text(JsonUtil.toJson(usage))
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AgentEvent ofCapabilityTokenUsage(String taskId, int round, String capName,
                                                    AgentTokenUsageEvent usage) {
        return AgentEvent.builder()
                .taskId(taskId)
                .round(round)
                .type(EventType.CAPABILITY_TOKEN_USAGE)
                .tokenUsage(usage)
                .text("[" + capName + "] " + JsonUtil.toJson(usage))
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static AgentEvent ofTaskTokenSummary(String taskId, int round,
                                                AiTokenUsage total, Map<String, AiTokenUsage> byModel,
                                                long elapsedMs, long llmMs, Map<String, Long> llmMsByModel) {
        // LongNode prevents JsonUtil's Long→String serializer from turning numbers into quoted strings
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("by_model", byModel);
        summary.put("elapsed_ms", new LongNode(elapsedMs));
        summary.put("llm_ms", new LongNode(llmMs));
        if (llmMsByModel != null && !llmMsByModel.isEmpty()) {
            Map<String, LongNode> nodeMap = new LinkedHashMap<>();
            llmMsByModel.forEach((k, v) -> nodeMap.put(k, new LongNode(v)));
            summary.put("llm_ms_by_model", nodeMap);
        }
        return AgentEvent.builder()
                .taskId(taskId).round(round)
                .type(EventType.TASK_TOKEN_SUMMARY)
                .text(JsonUtil.toJson(summary))
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
