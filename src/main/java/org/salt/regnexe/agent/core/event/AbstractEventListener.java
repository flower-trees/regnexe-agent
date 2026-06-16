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
 * Convenience base class for custom {@link AgentEventListener} implementations.
 *
 * <p>Subclasses inherit built-in filtering via constructor flags and only need to
 * implement {@link #onEvent}; no need to override {@link #shouldHandle} for the
 * common token / LLM suppression cases.
 *
 * <p>Constructor flags:
 * <ul>
 *   <li>{@code showTokenEvents} — controls TOKEN_USAGE / CAPABILITY_TOKEN_USAGE / TASK_TOKEN_SUMMARY</li>
 *   <li>{@code showLlmEvents}   — controls all *_LLM_RESPONDED events</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * public class MyEventListener extends AbstractEventListener {
 *     public MyEventListener() {
 *         super(false, false);   // suppress token and LLM events
 *     }
 *     public void onEvent(AgentEvent event) { ... }
 * }
 * }</pre>
 */
public abstract class AbstractEventListener implements AgentEventListener {

    private final boolean showTokenEvents;
    private final boolean showLlmEvents;

    protected AbstractEventListener() {
        this(false, false);
    }

    protected AbstractEventListener(boolean showTokenEvents, boolean showLlmEvents) {
        this.showTokenEvents = showTokenEvents;
        this.showLlmEvents = showLlmEvents;
    }

    @Override
    public boolean shouldHandle(EventType type) {
        if (!showTokenEvents && isTokenEvent(type)) return false;
        if (!showLlmEvents   && isLlmEvent(type))   return false;
        return true;
    }

    protected static boolean isTokenEvent(EventType type) {
        return switch (type) {
            case TOKEN_USAGE, CAPABILITY_TOKEN_USAGE, TASK_TOKEN_SUMMARY -> true;
            default -> false;
        };
    }

    protected static boolean isLlmEvent(EventType type) {
        return switch (type) {
            case LLM_RESPONDED, PLAN_LLM_RESPONDED, SKILL_LLM_RESPONDED,
                 AGENT_LLM_RESPONDED, REFLECT_LLM_RESPONDED -> true;
            default -> false;
        };
    }
}
