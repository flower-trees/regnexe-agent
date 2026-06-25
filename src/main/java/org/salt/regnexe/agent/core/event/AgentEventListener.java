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

@FunctionalInterface
public interface AgentEventListener {

    AgentEventListener NO_OP = event -> {};

    void onEvent(AgentEvent event);

    /**
     * Override to declare which event types this listener cares about.
     * The framework calls {@link #dispatch} instead of {@link #onEvent} directly,
     * so returning {@code false} here prevents {@code onEvent} from being invoked at all.
     * Default: handle every event.
     */
    default boolean shouldHandle(EventType type) {
        return true;
    }

    /**
     * Framework entry point. Checks {@link #shouldHandle} before delegating to {@link #onEvent}.
     * Lambda / NO_OP implementations inherit this without any change.
     */
    default void dispatch(AgentEvent event) {
        if (shouldHandle(event.getType())) {
            onEvent(event);
        }
    }
}
