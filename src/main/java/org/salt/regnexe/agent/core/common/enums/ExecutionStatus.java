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

package org.salt.regnexe.agent.core.common.enums;

import lombok.Getter;

/**
 * Executor invocation result status
 */
@Getter
public enum ExecutionStatus {

    /**
     * Execution completed successfully
     */
    SUCCESS("success"),

    /**
     * Execution was interrupted by a stop signal
     */
    STOPPED("stopped"),

    /**
     * Execution terminated with an error
     */
    FAILED("failed");

    private final String code;

    ExecutionStatus(String code) {
        this.code = code;
    }

    /**
     * Returns the enum constant matching the given code.
     */
    public static ExecutionStatus fromCode(String code) {
        for (ExecutionStatus it : ExecutionStatus.values()) {
            if (it.getCode().equals(code)) {
                return it;
            }
        }
        throw new IllegalArgumentException("Invalid ExecutionStatus code: " + code);
    }
}
