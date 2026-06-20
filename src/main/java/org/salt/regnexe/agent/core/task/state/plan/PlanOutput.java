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

package org.salt.regnexe.agent.core.task.state.plan;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Planner output for one round
 */
@Data
public class PlanOutput {

    /**
     * Natural-language execution instruction passed to Executor
     */
    private String narrative;

    /**
     * Capability ids selected from candidates for this round
     */
    private List<String> selectedCapabilityIds;

    /**
     * Per-capability input descriptions: guides CapabilityExecutor on what context
     * to pass when invoking each capability. Values are natural language and may
     * reference other capabilities' outputs (e.g. "pass the output of chapter_writer").
     */
    private Map<String, String> capabilityInputDescriptions;

    /**
     * Controls whether Executor should return the final capability result directly
     * or ask the inner agent to synthesize all relevant observations.
     */
    private ResultStrategy resultStrategy;

    /**
     * User-facing requirements that must be covered by the final execution answer.
     */
    private List<String> finalAnswerRequirements;

    /**
     * Planner's reasoning, for auditing
     */
    private String reasoning;
}
