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

import lombok.extern.slf4j.Slf4j;

/**
 * Routes each event through SLF4J instead of stdout. Use this in production so agent tracing
 * flows into the host application's existing logging pipeline (file/JSON appenders, log
 * aggregation, etc.) instead of a separate println stream like {@link ConsoleEventListener}.
 */
@Slf4j
public class Slf4jEventListener extends AbstractEventListener {

    public Slf4jEventListener() {
        super();
    }

    public Slf4jEventListener(boolean showTokenEvents, boolean showLlmEvents) {
        super(showTokenEvents, showLlmEvents);
    }

    @Override
    public void onEvent(AgentEvent event) {
        log.info(format(event));
    }
}
