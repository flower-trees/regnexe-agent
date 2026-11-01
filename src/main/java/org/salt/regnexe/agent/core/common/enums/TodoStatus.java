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
 * One {@code write_todos} item's progress state — mirrors Claude Code's own TodoWrite tool.
 */
@Getter
public enum TodoStatus {

    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed");

    private final String code;

    TodoStatus(String code) {
        this.code = code;
    }

    public static TodoStatus fromCode(String code) {
        for (TodoStatus it : TodoStatus.values()) {
            if (it.getCode().equals(code)) {
                return it;
            }
        }
        throw new IllegalArgumentException("Invalid TodoStatus code: " + code);
    }
}
