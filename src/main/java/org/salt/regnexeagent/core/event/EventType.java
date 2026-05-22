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

package org.salt.regnexeagent.core.event;

public enum EventType {

    // Outer loop
    AGENT_STARTED,
    SEARCH_COMPLETED,
    PLAN_COMPLETED,
    EXECUTION_COMPLETED,
    REFLECTION_COMPLETED,
    AGENT_COMPLETED,

    // Inner loop — emitted by McpAgentExecutor hooks
    LLM_RESPONDED,
    TOOL_CALLED,
    TOOL_RESULT
}
