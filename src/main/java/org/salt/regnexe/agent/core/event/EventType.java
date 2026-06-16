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

public enum EventType {

    // Outer loop
    AGENT_STARTED,
    SEARCH_STARTED,
    SEARCH_COMPLETED,
    PLAN_STARTED,
    PLAN_COMPLETED,
    EXECUTION_STARTED,
    EXECUTION_COMPLETED,
    REFLECTION_STARTED,
    REFLECTION_COMPLETED,
    AGENT_COMPLETED,

    // Inner loop — emitted by McpAgentExecutor hooks (outer executor)
    LLM_RESPONDED,
    TOOL_CALLED,
    TOOL_RESULT,
    TOKEN_USAGE,

    // Phase-specific LLM hooks for trace clarity
    PLAN_LLM_RESPONDED,
    REFLECT_LLM_RESPONDED,
    SKILL_LLM_RESPONDED,
    AGENT_LLM_RESPONDED,

    // Token usage from inner capability (Skill / SubAgent) LLM calls
    CAPABILITY_TOKEN_USAGE,

    // Aggregated token summary emitted once per task just before AGENT_COMPLETED
    TASK_TOKEN_SUMMARY
}
