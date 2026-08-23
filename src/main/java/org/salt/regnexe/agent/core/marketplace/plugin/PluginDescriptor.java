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

package org.salt.regnexe.agent.core.marketplace.plugin;

import lombok.Data;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityType;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Describes a plugin and the capabilities it registers into the Marketplace.
 *
 * <p>Hand-rolled builder (not Lombok {@code @Builder}) so that {@link #builder tool},
 * {@link #builder skillConfig}, {@link #builder subAgentConfig} can wrap raw configs into
 * {@link CapabilityDescriptor}s automatically, in addition to an explicit
 * {@link PluginDescriptorBuilder#capabilities} list.
 */
@Data
public class PluginDescriptor {

    private String pluginId;

    private String version;

    private String name;

    private String description;

    private List<String> tags;

    private List<CapabilityDescriptor> capabilities;

    private Map<String, Object> metadata;

    public static PluginDescriptorBuilder builder() {
        return new PluginDescriptorBuilder();
    }

    public static class PluginDescriptorBuilder {

        private String pluginId;
        private String version;
        private String name;
        private String description;
        private List<String> tags;
        private List<CapabilityDescriptor> capabilities;
        private Map<String, Object> metadata;

        private final List<Tool> tools = new ArrayList<>();
        private final List<SkillConfig> skillConfigs = new ArrayList<>();
        private final List<SubAgentConfig> subAgentConfigs = new ArrayList<>();

        public PluginDescriptorBuilder pluginId(String pluginId) {
            this.pluginId = pluginId;
            return this;
        }

        public PluginDescriptorBuilder version(String version) {
            this.version = version;
            return this;
        }

        public PluginDescriptorBuilder name(String name) {
            this.name = name;
            return this;
        }

        public PluginDescriptorBuilder description(String description) {
            this.description = description;
            return this;
        }

        public PluginDescriptorBuilder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        public PluginDescriptorBuilder capabilities(List<CapabilityDescriptor> capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        public PluginDescriptorBuilder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        /** Wraps each Tool into an MCP_TOOL capability, id'd as {@code pluginId + "." + tool.getName()}. */
        public PluginDescriptorBuilder tool(Tool... tools) {
            Collections.addAll(this.tools, tools);
            return this;
        }

        /** Wraps each SkillConfig into a SKILL capability, id'd as {@code pluginId + "." + config.getName()}. */
        public PluginDescriptorBuilder skillConfig(SkillConfig... configs) {
            Collections.addAll(this.skillConfigs, configs);
            return this;
        }

        /** Wraps each SubAgentConfig into a SUB_AGENT capability, id'd as {@code pluginId + "." + config.getName()}. */
        public PluginDescriptorBuilder subAgentConfig(SubAgentConfig... configs) {
            Collections.addAll(this.subAgentConfigs, configs);
            return this;
        }

        public PluginDescriptor build() {
            if (!tools.isEmpty() || !skillConfigs.isEmpty() || !subAgentConfigs.isEmpty()) {
                if (pluginId == null || pluginId.isBlank()) {
                    throw new IllegalStateException(
                            "pluginId must be set before adding tool/skillConfig/subAgentConfig");
                }
            }

            List<CapabilityDescriptor> allCapabilities =
                    new ArrayList<>(capabilities != null ? capabilities : List.of());
            tools.forEach(t -> allCapabilities.add(CapabilityDescriptor.builder()
                    .capabilityId(pluginId + "." + t.getName())
                    .pluginId(pluginId)
                    .type(CapabilityType.MCP_TOOL)
                    .tool(t)
                    .build()));
            skillConfigs.forEach(c -> allCapabilities.add(CapabilityDescriptor.builder()
                    .capabilityId(pluginId + "." + c.getName())
                    .pluginId(pluginId)
                    .type(CapabilityType.SKILL)
                    .skillConfig(c)
                    .build()));
            subAgentConfigs.forEach(c -> allCapabilities.add(CapabilityDescriptor.builder()
                    .capabilityId(pluginId + "." + c.getName())
                    .pluginId(pluginId)
                    .type(CapabilityType.SUB_AGENT)
                    .subAgentConfig(c)
                    .build()));

            PluginDescriptor descriptor = new PluginDescriptor();
            descriptor.setPluginId(pluginId);
            descriptor.setVersion(version);
            descriptor.setName(name);
            descriptor.setDescription(description);
            descriptor.setTags(tags);
            descriptor.setCapabilities(allCapabilities);
            descriptor.setMetadata(metadata);
            return descriptor;
        }
    }
}
