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

package org.salt.heliosagent.core.task.worker;

import lombok.extern.slf4j.Slf4j;
import org.salt.function.flow.node.FlowNode;

/**
 * Evaluates the execution result and decides whether to FINISH, CONTINUE, or ESCALATE.
 * Writes only to state (not to ContextBus); the next round's workers read
 * the hint from state.getRounds().
 */
@Slf4j
public class Reflector extends FlowNode<Object, Object> implements Worker {

    @Override
    public Object process(Object input) {
        // TODO
        return null;
    }

    @Override
    public void stop() {
        // LLM call is synchronous; stop signal is checked between rounds
    }
}
