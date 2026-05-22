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

package org.salt.regnexeagent.core.task;

import org.salt.regnexeagent.core.task.state.RoundRecord;
import org.salt.regnexeagent.core.task.state.TaskExecutionState;
import org.salt.regnexeagent.core.task.state.reflection.ReflectionDecision;

import java.util.List;

/**
 * Returns the finalText from the last round that produced an execution result.
 */
public class DefaultResultComposer implements ResultComposer {

    @Override
    public String compose(TaskExecutionState state, ReflectionDecision decision) {
        List<RoundRecord> rounds = state.getRounds();
        if (rounds == null || rounds.isEmpty()) {
            return null;
        }
        for (int i = rounds.size() - 1; i >= 0; i--) {
            RoundRecord round = rounds.get(i);
            if (round.getExecutionResult() != null
                    && round.getExecutionResult().getFinalText() != null) {
                return round.getExecutionResult().getFinalText();
            }
        }
        return null;
    }
}
