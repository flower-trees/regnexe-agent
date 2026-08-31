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

package org.salt.regnexe.agent.core.task.store;

import org.salt.regnexe.agent.core.task.state.TaskExecutionState;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for TaskExecutionState (the task ledger).
 * Enables cross-round checkpoint and resume.
 */
public interface TaskStore {

    void save(TaskExecutionState state);

    Optional<TaskExecutionState> load(String taskId);

    /** Equivalent to {@link #listResumable(String, boolean)} with {@code includeFailed=false}. */
    default List<TaskExecutionState> listResumable(String sessionId) {
        return listResumable(sessionId, false);
    }

    /**
     * Tasks eligible for {@code resume()}. Normally just PAUSED/RUNNING — a task the harness
     * itself gave up on (FAILED) is excluded by default, since blindly retrying it risks hitting
     * the same unrecoverable error again. {@code includeFailed=true} (CLI: {@code --force-resume})
     * opts back in for the case where the failure's actual cause has been fixed since (e.g. the
     * account that hit a 402 has been topped up, or the model config was switched to a working
     * vendor) — the task's full round history (including the FAILED round's own captured error)
     * is still there in {@link TaskExecutionState}, untouched either way; this only changes
     * whether it's exposed for resuming.
     */
    List<TaskExecutionState> listResumable(String sessionId, boolean includeFailed);

    void markFinished(String taskId);
}
