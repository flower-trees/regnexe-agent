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
     * Private tools injected directly into the SubAgent's own tool registry.
     * These are never exposed to the master LLM and do not appear in the marketplace.
     * Skills always inherit the master tool set and must not define private tools.
     */
    private List<Tool> ownTools;

    /**
     * Extra kwargs passed to the SubAgent LLM builder when this capability's model is
     * resolved (e.g. temperature, thinking). Null = provider defaults.
     * Skills always inherit the master LLM and must not define model kwargs.
     */
    private java.util.Map<String, Object> modelKwargs;

    public static CapabilityDescriptorBuilder builder() {
        return new CapabilityDescriptorBuilder();
    }

    public static class CapabilityDescriptorBuilder {

        private String capabilityId;
        private String pluginId;
        private CapabilityType type;
        private String name;
        private String description;
        private List<String> tags;
        private Tool tool;
        private SkillConfig skillConfig;
        private SubAgentConfig subAgentConfig;
        private List<Tool> ownTools;
        private java.util.Map<String, Object> modelKwargs;

        public CapabilityDescriptorBuilder capabilityId(String capabilityId) {
            this.capabilityId = capabilityId;
            return this;
        }

        public CapabilityDescriptorBuilder pluginId(String pluginId) {
            this.pluginId = pluginId;
            return this;
        }

        public CapabilityDescriptorBuilder type(CapabilityType type) {
            this.type = type;
            return this;
        }

        public CapabilityDescriptorBuilder name(String name) {
            this.name = name;
            return this;
        }

        public CapabilityDescriptorBuilder description(String description) {
            this.description = description;
            return this;
        }

        public CapabilityDescriptorBuilder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        public CapabilityDescriptorBuilder tool(Tool tool) {
            this.tool = tool;
            return this;
        }

        public CapabilityDescriptorBuilder skillConfig(SkillConfig skillConfig) {
            this.skillConfig = skillConfig;
            return this;
        }

        public CapabilityDescriptorBuilder subAgentConfig(SubAgentConfig subAgentConfig) {
            this.subAgentConfig = subAgentConfig;
            return this;
        }

        public CapabilityDescriptorBuilder ownTools(List<Tool> ownTools) {
            this.ownTools = ownTools;
            return this;
        }

        public CapabilityDescriptorBuilder modelKwargs(java.util.Map<String, Object> modelKwargs) {
            this.modelKwargs = modelKwargs;
            return this;
        }

        public CapabilityDescriptor build() {
            validate();

            if (type == CapabilityType.MCP_TOOL && tool != null) {
                fillNameAndDescription(tool.getName(), tool.getDescription());
            } else if (type == CapabilityType.SKILL && skillConfig != null) {
                fillNameAndDescription(skillConfig.getName(), skillConfig.getDescription());
            } else if (type == CapabilityType.SUB_AGENT && subAgentConfig != null) {
                fillNameAndDescription(subAgentConfig.getName(), subAgentConfig.getDescription());
            }

            CapabilityDescriptor descriptor = new CapabilityDescriptor();
            descriptor.setCapabilityId(capabilityId);
            descriptor.setPluginId(pluginId);
            descriptor.setType(type);
            descriptor.setName(name);
            descriptor.setDescription(description);
            descriptor.setTags(tags);
            descriptor.setTool(tool);
            descriptor.setSkillConfig(skillConfig);
            descriptor.setSubAgentConfig(subAgentConfig);
            descriptor.setOwnTools(ownTools);
            descriptor.setModelKwargs(modelKwargs);
            return descriptor;
        }

        private void validate() {
            if (type == CapabilityType.SKILL
                    && (tool != null || hasOwnTools() || modelKwargs != null)) {
                throw new IllegalArgumentException(
                        "SKILL capabilities must inherit tools and LLM; do not set tool, ownTools, or modelKwargs.");
            }
        }

        private boolean hasOwnTools() {
            return ownTools != null && !ownTools.isEmpty();
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }

        private void fillNameAndDescription(String defaultName, String defaultDescription) {
            if (isBlank(name)) {
                name = defaultName;
            }
            if (isBlank(description)) {
                description = defaultDescription;
            }
        }
    }
}
