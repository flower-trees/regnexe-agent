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
import org.salt.heliosagent.core.task.state.capability.CapabilitySearchResult;
import org.salt.heliosagent.core.task.state.execution.ExecutionOutput;
import org.salt.heliosagent.core.task.state.plan.PlanOutput;
import org.salt.heliosagent.core.task.state.reflection.ReflectionDecision;

/**
 * Complete record of one loop round.
 * Fields are filled sequentially by each node; a null field indicates
 * where execution was interrupted, serving as a resume marker.
 */
@Data
public class RoundRecord {

    private int roundNumber;

    /**
     * Set when Searcher ran; null when Searcher was skipped this round
     */
    private CapabilitySearchResult search;

    private PlanOutput plan;

    private ExecutionOutput executionResult;

    private ReflectionDecision reflection;

    /**
     * Index into TaskExecutionState.searchResults indicating which
     * candidates were used in this round
     */
    private int searchResultVersion;

    private long startedAt;

    private long endedAt;
}
