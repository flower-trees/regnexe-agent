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
 */
public class ConsoleEventListener implements AgentEventListener {

    protected String format(AgentEvent event) {
        String prefix = switch (event.getType()) {
            case AGENT_STARTED        -> "[Agent Start   ]";
            case SEARCH_COMPLETED     -> "[Search Result ]";
            case PLAN_LLM_RESPONDED   -> "[Plan LLM      ]";
            case PLAN_COMPLETED       -> "[Plan Result   ]";
            case TOOL_CALLED          -> "[TOOL Call     ]";
            case SKILL_LLM_RESPONDED  -> "[TOOL Skill LLM]";
            case AGENT_LLM_RESPONDED  -> "[TOOL SubAgent LLM]";
            case TOOL_RESULT          -> "[TOOL Result   ]";
            case LLM_RESPONDED        -> "[Execute LLM   ]";
            case EXECUTION_COMPLETED  -> "[Execute Result]";
            case REFLECT_LLM_RESPONDED-> "[Reflect LLM   ]";
            case REFLECTION_COMPLETED -> "[Reflect Result]";
            case AGENT_COMPLETED      -> "[Agent Done    ]";
            case TOKEN_USAGE          -> "[Token Usage   ]";
            case CAPABILITY_TOKEN_USAGE -> "[Cap Token Usage]";
            case TASK_TOKEN_SUMMARY    -> "[Task Token Usage]";
        };
        return String.format("%s R%d %s", prefix, event.getRound(), event.getText());
    }

    @Override
    public void onEvent(AgentEvent event) {
        System.out.println(format(event));
    }
}
