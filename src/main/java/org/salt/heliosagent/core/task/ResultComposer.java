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

package org.salt.heliosagent.core.task;

import org.salt.heliosagent.core.task.state.TaskExecutionState;
import org.salt.heliosagent.core.task.state.reflection.ReflectionDecision;

/**
 * Composes the final answer after the loop exits with FINISH.
 */
public interface ResultComposer {

    String compose(TaskExecutionState state, ReflectionDecision decision);
}
