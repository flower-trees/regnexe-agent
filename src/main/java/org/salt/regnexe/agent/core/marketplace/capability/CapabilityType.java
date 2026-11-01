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

package org.salt.regnexe.agent.core.marketplace.capability;

import lombok.Getter;

/**
 * Capability type registered in the Marketplace
 */
@Getter
public enum CapabilityType {

    /**
     * j-langchain Skill
     */
    SKILL("skill"),

    /**
     * j-langchain SubAgent
     */
    SUB_AGENT("sub_agent"),

    /**
     * MCP Tool (local NPX or remote HTTP)
     */
    MCP_TOOL("mcp_tool");

    private final String code;

    CapabilityType(String code) {
        this.code = code;
    }

    /**
     * Returns the enum constant matching the given code.
     */
    public static CapabilityType fromCode(String code) {
        for (CapabilityType it : CapabilityType.values()) {
            if (it.getCode().equals(code)) {
                return it;
            }
        }
        throw new IllegalArgumentException("Invalid CapabilityType code: " + code);
    }
}
