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

package org.salt.regnexe.agent.core.market.plugin;

import lombok.Builder;
import lombok.Data;
import org.salt.regnexe.agent.core.common.enums.CapabilityType;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.regnexe.agent.core.market.PluginManager;

import java.util.List;

/**
 * Describes a single capability exposed by a plugin.
 * The description and tags are what Searcher and Planner see.
 *
 * <p>Exactly one backing field is set based on {@link #type}:
 * <ul>
 *   <li>{@code MCP_TOOL}  → {@link #tool} is set (pre-built, ready to invoke)</li>
 *   <li>{@code SKILL}     → {@link #skillConfig} is set (built into a Tool at execution time)</li>
 *   <li>{@code SUB_AGENT} → {@link #subAgentConfig} is set (built into a Tool at execution time)</li>
 * </ul>
 * Lazy instantiation of Skill / SubAgent keeps {@link PluginManager}
 * free of LLM dependencies at load time.
 */
@Data
@Builder
public class CapabilityDescriptor {

    private String capabilityId;

    private String pluginId;

    private CapabilityType type;

    private String name;

    private String description;

    private List<String> tags;

    /** Set for MCP_TOOL: pre-built, invoked directly. */
    private Tool tool;

    /** Set for SKILL: built into a Tool by CapabilityExecutor at execution time. */
    private SkillConfig skillConfig;

    /** Set for SUB_AGENT: built into a Tool by CapabilityExecutor at execution time. */
    private SubAgentConfig subAgentConfig;

    /**
     * Private tools injected directly into the Skill/SubAgent's own tool registry.
     * These are never exposed to the master LLM and do not appear in the marketplace.
     * Use this for internal tools that should only be callable by this specific capability.
     */
    private List<Tool> ownTools;
}
