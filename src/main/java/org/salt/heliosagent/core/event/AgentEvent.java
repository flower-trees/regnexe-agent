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

package org.salt.heliosagent.core.event;

import lombok.Builder;
import lombok.Value;

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
}
