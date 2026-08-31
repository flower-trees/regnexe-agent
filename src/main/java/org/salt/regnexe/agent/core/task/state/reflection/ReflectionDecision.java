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

package org.salt.regnexe.agent.core.task.state.reflection;

import lombok.Data;
import org.salt.regnexe.agent.core.common.enums.ReflectionAction;

/**
 * Reflector decision for one round
 */
@Data
public class ReflectionDecision {

    private ReflectionAction action;

    private String reason;

    /**
     * Guidance for the next round; null when action is FINISH or ESCALATE
     */
    private ReflectionHint hintForNext;

    /**
     * Structured hand-off summary for the next round's Planner — what this round actually
     * accomplished, what failed and why, what's left. See docs/design/08-round-handoff-redesign.md.
     *
     * <p>Deliberately separate from {@code ExecutionOutput.finalText}: that field is Execute's
     * own (cheaper-model) concise answer to the user's original goal, written under a "be
     * concise" instruction — a different audience and purpose than a technical hand-off for the
     * next round's planning. This field is authored by Reflector (the stronger-model role) from
     * the round's full, untruncated {@code tool_executions}, not from the last tool result alone.
     */
    private String roundSummary;
}
