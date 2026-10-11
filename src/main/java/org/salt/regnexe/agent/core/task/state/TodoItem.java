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
import org.salt.regnexe.agent.core.common.enums.TodoStatus;

/**
 * One item of the executor's self-maintained task checklist (the {@code write_todos} tool).
 * Model-driven, optional bookkeeping — not produced by Plan and not required for any round to
 * complete. See {@link TaskExecutionState#getTodos()}.
 */
@Data
public class TodoItem {

    private String content;

    private TodoStatus status;
}
