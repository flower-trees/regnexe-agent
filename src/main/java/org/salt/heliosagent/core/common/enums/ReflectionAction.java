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

package org.salt.heliosagent.core.common.enums;

import lombok.Getter;

/**
 * Reflector decision action
 */
@Getter
public enum ReflectionAction {

    /**
     * Task is done; exit the loop and compose the final answer
     */
    FINISH("finish"),

    /**
     * Proceed to the next round with a hint for the next iteration
     */
    CONTINUE("continue"),

    /**
     * Escalate for human intervention and exit the loop
     */
    ESCALATE("escalate");

    private final String code;

    ReflectionAction(String code) {
        this.code = code;
    }

    /**
     * Returns the enum constant matching the given code.
     */
    public static ReflectionAction fromCode(String code) {
        for (ReflectionAction it : ReflectionAction.values()) {
            if (it.getCode().equals(code)) {
                return it;
            }
        }
        throw new IllegalArgumentException("Invalid ReflectionAction code: " + code);
    }
}
