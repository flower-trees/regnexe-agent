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

    protected String format(AgentEvent event) {
        String prefix = switch (event.getType()) {
            case AGENT_STARTED        -> "[Agent Start   ]";
            case SEARCH_STARTED       -> "[Search Input  ]";
            case SEARCH_COMPLETED     -> "[Search Result ]";
            case PLAN_STARTED         -> "[Plan Input    ]";
            case PLAN_LLM_RESPONDED   -> "[Plan LLM      ]";
            case PLAN_COMPLETED       -> "[Plan Result   ]";
            case EXECUTION_STARTED    -> "[Execute Input ]";
            case TOOL_CALLED          -> "[TOOL Call     ]";
            case SKILL_LLM_RESPONDED  -> "[TOOL Skill LLM]";
            case AGENT_LLM_RESPONDED  -> "[TOOL SubAgent LLM]";
            case TOOL_RESULT          -> "[TOOL Result   ]";
            case LLM_RESPONDED        -> "[Execute LLM   ]";
            case EXECUTION_COMPLETED  -> "[Execute Result]";
            case REFLECTION_STARTED   -> "[Reflect Input ]";
            case REFLECT_LLM_RESPONDED-> "[Reflect LLM   ]";
            case REFLECTION_COMPLETED -> "[Reflect Result]";
            case AGENT_COMPLETED      -> "[Agent Done    ]";
            case LOOP_PAUSE_REASON    -> "[Paused        ]";
            case TOKEN_USAGE          -> "[Token Usage   ]";
            case CAPABILITY_TOKEN_USAGE -> "[Cap Token Usage]";
            case TASK_TOKEN_SUMMARY    -> "[Task Token Usage]";
        };
        return String.format("%s R%d %s", prefix, event.getRound(), event.getText());
    }
}
