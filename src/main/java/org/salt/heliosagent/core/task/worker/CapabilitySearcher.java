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
 * Queries the Marketplace for capability candidates matching the current goal.
 * First round always runs; subsequent rounds run only when the previous
 * ReflectionHint requests a re-search.
 */
@Slf4j
public class CapabilitySearcher extends FlowNode<Object, Object> implements Worker {

    @Override
    public Object process(Object input) {
        // TODO
        return null;
    }

    @Override
    public void stop() {
        // Fast retrieval step — no long-running inner executor to stop
    }
}
