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

package org.salt.regnexeagent.core.common.enums;

import lombok.Getter;

/**
 * Task execution status
 */
@Getter
public enum TaskStatus {

    /**
     * Task is actively running
     */
    RUNNING("running"),

    /**
     * Task has been paused and can be resumed
     */
    PAUSED("paused"),

    /**
     * Task completed successfully
     */
    FINISHED("finished"),

    /**
     * Task escalated for human intervention
     */
    ESCALATED("escalated"),

    /**
     * Task terminated with an error
     */
    FAILED("failed"),

    /**
     * Task exceeded the maximum number of rounds
     */
    TIMEOUT("timeout");

    private final String code;

    TaskStatus(String code) {
        this.code = code;
    }

    /**
     * Returns the enum constant matching the given code.
     */
    public static TaskStatus fromCode(String code) {
        for (TaskStatus it : TaskStatus.values()) {
            if (it.getCode().equals(code)) {
                return it;
            }
        }
        throw new IllegalArgumentException("Invalid TaskStatus code: " + code);
    }
}
