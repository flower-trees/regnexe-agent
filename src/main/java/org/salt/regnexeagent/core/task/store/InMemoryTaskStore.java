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

package org.salt.regnexeagent.core.task.store;

import org.salt.regnexeagent.core.common.enums.TaskStatus;
import org.salt.regnexeagent.core.task.state.TaskExecutionState;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory TaskStore for M0. State objects are stored by reference;
 * no serialization occurs, so updates to state are reflected immediately.
 */
public class InMemoryTaskStore implements TaskStore {

    private final Map<String, TaskExecutionState> store = new ConcurrentHashMap<>();

    @Override
    public void save(TaskExecutionState state) {
        store.put(state.getTaskId(), state);
    }

    @Override
    public Optional<TaskExecutionState> load(String taskId) {
        return Optional.ofNullable(store.get(taskId));
    }

    @Override
    public List<TaskExecutionState> listResumable(String sessionId) {
        return store.values().stream()
                .filter(s -> sessionId.equals(s.getSessionId())
                          && s.getStatus() == TaskStatus.PAUSED)
                .toList();
    }

    @Override
    public void markFinished(String taskId) {
        // State status is already updated by the time this is called; no-op here.
    }
}
