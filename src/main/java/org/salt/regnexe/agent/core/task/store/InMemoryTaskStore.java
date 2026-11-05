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

import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory TaskStore for M0. Snapshots and revisions are isolated, including nested round and tool records.
 */
public class InMemoryTaskStore implements TaskStore {

    private final Map<String, TaskExecutionState> store = new ConcurrentHashMap<>();

    @Override
    public synchronized void save(TaskExecutionState state) {
        TaskExecutionState previous = store.get(state.getTaskId());
        if ((previous == null && state.getVersion() != 0)
                || (previous != null && previous.getVersion() != state.getVersion())) {
            throw new TaskConflictException(state.getTaskId());
        }
        TaskExecutionState saved = snapshot(state);
        saved.setVersion(state.getVersion() + 1);
        store.put(state.getTaskId(), saved);
        state.setVersion(saved.getVersion());
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper SNAPSHOTS = TaskMessageDeserializer.loadMapper();

    private TaskExecutionState snapshot(TaskExecutionState state) {
        try {
            return SNAPSHOTS.readValue(SNAPSHOTS.writeValueAsBytes(state), TaskExecutionState.class);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot snapshot task: " + state.getTaskId(), e);
        }
    }

    @Override
    public Optional<TaskExecutionState> load(String taskId) {
        return Optional.ofNullable(store.get(taskId)).map(this::snapshot);
    }

    @Override
    public void markFinished(String taskId) {
        // State status is already updated by the time this is called; no-op here.
    }

    @Override
    public List<TaskExecutionState> listResumable(String sessionId) {
        return store.values().stream()
                .filter(s -> sessionId.equals(s.getSessionId()) && s.getStatus() != TaskStatus.FINISHED)
                .map(this::snapshot).toList();
    }
}
