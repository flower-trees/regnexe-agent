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

package org.salt.regnexe.agent.core.task.state;

import lombok.Data;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.task.state.capability.CapabilitySearchResult;

import java.util.List;
import java.util.Map;

/**
 * The task ledger. Tracks every round and accumulates search results across the loop.
 */
@Data
public class TaskExecutionState {

    // --- Fixed at creation ---

    private String taskId;

    private String sessionId;

    private TaskRequest request;

    private int maxRounds;

    private long createdAt;

    // --- Accumulated ---

    private TaskStatus status;

    private int currentRound;

    private long updatedAt;

    /**
     * Top-level list; appended when Searcher runs a new search.
     * Multiple rounds may share one entry via RoundRecord.searchResultVersion.
     */
    private List<CapabilitySearchResult> searchResults;

    private List<RoundRecord> rounds;

    /**
     * Last top-level tool result produced by the current execution.
     * Used when execution delegates to a skill/sub-agent and the raw tool result
     * should be reflected/composed without a second LLM rewrite.
     */
    private String lastToolResult;

    /**
     * Reference to the Context in ContextManager; not embedded inline
     */
    private String contextId;

    private Map<String, Object> metadata;
}
