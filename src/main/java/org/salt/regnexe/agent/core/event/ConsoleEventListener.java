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

package org.salt.regnexe.agent.core.event;

/**
 * Prints each event to stdout. Suitable for CLI and test output.
 * Extends {@link AbstractEventListener} to inherit token / LLM filtering via constructor flags.
 */
public class ConsoleEventListener extends AbstractEventListener {

    public ConsoleEventListener() {
        super();
    }

    public ConsoleEventListener(boolean showTokenEvents, boolean showLlmEvents) {
        super(showTokenEvents, showLlmEvents);
    }

    @Override
    public void onEvent(AgentEvent event) {
        System.out.println(format(event));
    }
}
