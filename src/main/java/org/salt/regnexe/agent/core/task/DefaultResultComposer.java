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

package org.salt.regnexe.agent.core.task;

import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.task.state.RoundRecord;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.reflection.ReflectionDecision;

import java.util.List;

/**
 * Composes the text returned to the caller when a task stops.
 *
 * <p>FINISHED tasks return the last round's {@code finalText} — that's Execute's own genuine
 * final answer to the user's goal, exactly what should be shown. Any other outcome (TIMEOUT,
 * ESCALATED, or a round that failed before ever finishing) prefers the last round's Reflector
 * {@code roundSummary} instead: since {@link org.salt.regnexe.agent.core.task.worker.CapabilityExecutor}
 * now leaves a failed round's {@code finalText} as a short fixed marker (see
 * docs/design/08-round-handoff-redesign.md), {@code roundSummary} is the only text that actually
 * says what was accomplished and why the task didn't finish. Never returns null — a task that
 * produced no usable text from either source still gets an honest, synthesized status line rather
 * than silently showing nothing to the caller.
 */
public class DefaultResultComposer implements ResultComposer {

    @Override
    public String compose(TaskExecutionState state, ReflectionDecision decision) {
        List<RoundRecord> rounds = state.getRounds();
        if (rounds == null || rounds.isEmpty()) {
            return "Task ended (" + state.getStatus() + ") with no rounds recorded.";
        }

        if (state.getStatus() != TaskStatus.FINISHED) {
            String summary = decision != null ? decision.getRoundSummary() : null;
            if (summary != null && !summary.isBlank()) {
                return summary;
            }
        }

        for (int i = rounds.size() - 1; i >= 0; i--) {
            RoundRecord round = rounds.get(i);
            if (round.getExecutionResult() != null
                    && round.getExecutionResult().getFinalText() != null
                    && !round.getExecutionResult().getFinalText().isBlank()) {
                return round.getExecutionResult().getFinalText();
            }
        }

        return "Task ended (" + state.getStatus() + ") after " + rounds.size()
                + " round(s) without a usable result.";
    }
}
