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

package org.salt.regnexe.agent.core.task.state.plan;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Defines how the executor should form the round's final execution text.
 */
public enum ResultStrategy {

    /**
     * The final selected capability is expected to produce the complete answer.
     */
    RETURN_LAST,

    /**
     * The final answer must synthesize multiple capability/tool observations.
     */
    SYNTHESIZE

    ;

    @JsonCreator
    public static ResultStrategy from(String value) {
        if (value == null || value.isBlank()) {
            return SYNTHESIZE;
        }
        String normalized = value.trim().toUpperCase();
        for (ResultStrategy strategy : values()) {
            if (strategy.name().equals(normalized)) {
                return strategy;
            }
        }
        return SYNTHESIZE;
    }
}
