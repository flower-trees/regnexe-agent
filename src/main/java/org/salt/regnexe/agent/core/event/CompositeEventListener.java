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

import java.util.List;

/**
 * Fans one event stream out to several listeners, in registration order. Each listener keeps its
 * own {@link AgentEventListener#shouldHandle} filter, and a listener that throws does not stop
 * the ones registered after it.
 */
@Slf4j
public class CompositeEventListener implements AgentEventListener {

    private final List<AgentEventListener> listeners;

    public CompositeEventListener(List<AgentEventListener> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    @Override
    public void onEvent(AgentEvent event) {
        for (AgentEventListener listener : listeners) {
            try {
                listener.dispatch(event);
            } catch (RuntimeException e) {
                log.warn("Event listener {} failed on {}: {}",
                        listener.getClass().getSimpleName(), event.getType(), e.getMessage());
            }
        }
    }
}
