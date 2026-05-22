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

    @Override
    public void onEvent(AgentEvent event) {
        String prefix = switch (event.getType()) {
            case AGENT_STARTED        -> "[Agent         ]";
            case SEARCH_COMPLETED     -> "[Search        ]";
            case PLAN_COMPLETED       -> "[Plan          ]";
            case TOOL_CALLED          -> "[Execute Call  ]";
            case TOOL_RESULT          -> "[Execute Result]";
            case LLM_RESPONDED        -> "[Execute LLM   ]";
            case EXECUTION_COMPLETED  -> "[Execute       ]";
            case REFLECTION_COMPLETED -> "[Reflect       ]";
            case AGENT_COMPLETED      -> "[Done          ]";
        };
        System.out.printf("%s R%d %s%n", prefix, event.getRound(), event.getText());
    }
}
