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

package org.salt.heliosagent.core.task.state;

import lombok.Data;

import java.util.Map;

/**
 * Immutable task input. All roles can read it; no role may modify it.
 */
@Data
public class TaskRequest {

    /**
     * The user's original goal
     */
    private String goal;

    /**
     * Structured inputs attached to the task
     */
    private Map<String, Object> inputs;

    /**
     * Submitter identifier, for auditing
     */
    private String submittedBy;

    /**
     * Session identifier, for auditing
     */
    private String sessionId;

    /**
     * Additional context supplied by the user when resuming a paused task.
     * Injected into the Planner prompt as a separate section so the LLM can
     * distinguish it from the original goal.
     */
    private String supplementInput;
}
