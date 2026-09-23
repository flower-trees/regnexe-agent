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
import org.salt.jlangchain.core.agent.memory.AgentStep;
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
     * The shared AgentTaskContext's completed steps, carried across rounds so Execute's own
     * conversation history (not a hand-rendered text summary) is what continues each round —
     * see docs/design/11-round-context-sharing-design.md. CapabilityExecutor replays these into a
     * freshly created AgentTaskContext at the start of each round (via addStep), then persists
     * whatever remains back here (via getCompletedSteps) once the round finishes. Compaction of
     * older steps into {@link #priorStepsSummary} happens inside that AgentTaskContext itself
     * (e.g. SlidingWindowContext) — regnexe only stores and replays, it never compacts.
     */
    private List<AgentStep> priorSteps;

    /**
     * The shared AgentTaskContext's compacted summary of steps already dropped out of
     * {@link #priorSteps} (see {@code AgentTaskContext#getEarlyStepsSummary()}). Restored into
     * the fresh context each round via {@code restoreSummary()} so a compacted summary isn't
     * regenerated from scratch every round.
     */
    private String priorStepsSummary;

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
