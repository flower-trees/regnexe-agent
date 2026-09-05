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

package org.salt.regnexe.agent.core.common.util;

import org.salt.regnexe.agent.core.task.state.RoundRecord;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;

import java.util.List;

public final class RoundRecords {

    private RoundRecords() {}

    /**
     * Returns the RoundRecord for {@code state.getCurrentRound()}, matched explicitly by
     * {@code roundNumber} rather than assuming it's simply the last list entry — that assumption
     * only held because {@code rounds} never used to shrink; it's no longer safe now that
     * {@link TaskExecutionState#getToolExecutions()} (a separate, flat, task-wide list) gets
     * periodically compacted (see docs/design/11-round-context-sharing-design.md). Was duplicated
     * three times (CapabilityExecutor/Reflector/TaskPlanner) as {@code rounds.get(rounds.size()-1)}.
     */
    public static RoundRecord current(TaskExecutionState state) {
        int roundNum = state.getCurrentRound();
        List<RoundRecord> rounds = state.getRounds();
        for (int i = rounds.size() - 1; i >= 0; i--) {
            if (rounds.get(i).getRoundNumber() == roundNum) {
                return rounds.get(i);
            }
        }
        throw new IllegalStateException("No RoundRecord found for round " + roundNum);
    }
}
