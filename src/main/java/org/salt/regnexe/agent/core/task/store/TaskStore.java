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
 * Enables cross-round checkpoint.
 */
public interface TaskStore {

    void save(TaskExecutionState state);

    Optional<TaskExecutionState> load(String taskId);

    void markFinished(String taskId);

    /**
     * Every task for this session worth continuing via {@code RegnexeAgent.resume()}: anything
     * except FINISHED. Includes RUNNING (a process killed before it got a chance to save a more
     * specific status — kill -9, a crash — should still be resumable, not silently lost), PAUSED
     * (a clean Ctrl+C), and FAILED/ESCALATED/TIMEOUT (all retryable, especially once the caller
     * can supply a fresh instruction on resume rather than blindly repeating whatever went wrong).
     */
    List<TaskExecutionState> listResumable(String sessionId);
}
