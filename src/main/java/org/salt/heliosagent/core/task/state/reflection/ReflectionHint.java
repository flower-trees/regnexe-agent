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

package org.salt.heliosagent.core.task.state.reflection;

import lombok.Data;

import java.util.List;

/**
 * Guidance produced by Reflector for the next round (only set when action is CONTINUE)
 */
@Data
public class ReflectionHint {

    // --- For Searcher ---

    /**
     * Whether Searcher should re-run a new search in the next round
     */
    private boolean requestResearch;

    private String searchDirection;

    private List<String> excludeCapabilityIds;

    // --- For Planner ---

    private String planAdjustment;

    private List<String> avoidCapabilityIds;

    // --- Common ---

    private String reason;
}
