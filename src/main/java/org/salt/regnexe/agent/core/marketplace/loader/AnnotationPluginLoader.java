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

package org.salt.regnexe.agent.core.marketplace.loader;

import lombok.extern.slf4j.Slf4j;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityType;
import org.salt.regnexe.agent.core.marketplace.plugin.AgentSkill;
import org.salt.regnexe.agent.core.marketplace.plugin.AgentSubAgent;
import org.salt.regnexe.agent.core.marketplace.plugin.Plugin;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginDescriptor;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.rag.tools.annotation.ToolScanner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Discovers {@code @Plugin} / {@code @AgentSkill} / {@code @AgentSubAgent} annotated classes,
 * either via classpath scanning ({@link #scanPackages}) or from an explicitly-supplied instance
 * ({@link #load}), and normalizes each into a {@link PluginDescriptor}.
 */
@Slf4j
public class AnnotationPluginLoader {

    /** Scans {@code basePackages} for annotated classes and loads each as its own PluginDescriptor. */
    public List<PluginDescriptor> scanPackages(String... basePackages) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Plugin.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(AgentSkill.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(AgentSubAgent.class));

        List<PluginDescriptor> result = new ArrayList<>();
        for (String pkg : basePackages) {
            scanner.findCandidateComponents(pkg).forEach(bd -> {
                try {
                    Class<?> clazz = Class.forName(bd.getBeanClassName());
                    Object instance = clazz.getDeclaredConstructor().newInstance();
                    PluginDescriptor descriptor = load(clazz, instance);
                    if (descriptor != null) result.add(descriptor);
                } catch (Exception e) {
                    log.warn("Failed to register annotated class '{}': {}", bd.getBeanClassName(), e.getMessage());
                }
            });
        }
        return result;
    }

    /** Loads an explicitly-supplied {@code @Plugin}/{@code @AgentSkill}/{@code @AgentSubAgent} instance. */
    public PluginDescriptor load(Object bean) {
        return load(bean.getClass(), bean);
    }

    /** Dispatches to the registration logic matching whichever capability annotation is present. */
    private PluginDescriptor load(Class<?> clazz, Object instance) {
        Plugin pluginAnn = clazz.getAnnotation(Plugin.class);
        AgentSkill skillAnn = clazz.getAnnotation(AgentSkill.class);
        AgentSubAgent subAgentAnn = clazz.getAnnotation(AgentSubAgent.class);

        if (pluginAnn != null) {
            return loadPluginBean(pluginAnn, instance);
        } else if (skillAnn != null) {
            return loadSkillBean(skillAnn, instance);
        } else if (subAgentAnn != null) {
            return loadSubAgentBean(subAgentAnn, instance);
        } else {
            log.warn("register() called on object without @Plugin/@AgentSkill/@AgentSubAgent: {}", clazz.getName());
            return null;
        }
    }

    /**
     * Scans {@code @AgentTool} methods (-> MCP_TOOL) and, additionally, any nested static class
     * annotated {@code @AgentSkill}/{@code @AgentSubAgent} (-> SKILL/SUB_AGENT) — all bundled
     * under this plugin's single {@code pluginId}, exactly like the file-based
     * {@code tools/}+{@code skills/}+{@code subagents/} layout or
     * {@code PluginDescriptor.builder().tool().skillConfig().subAgentConfig()}.
     */
    private PluginDescriptor loadPluginBean(Plugin ann, Object instance) {
        Class<?> clazz = instance.getClass();
        List<CapabilityDescriptor> caps = new ArrayList<>();

        ToolScanner.scan(instance).forEach(tool -> caps.add(CapabilityDescriptor.builder()
                .capabilityId(ann.id() + "." + tool.getName())
                .pluginId(ann.id())
                .type(CapabilityType.MCP_TOOL)
                .tool(tool)
                .build()));

        for (Class<?> nested : clazz.getDeclaredClasses()) {
            AgentSkill skillAnn = nested.getAnnotation(AgentSkill.class);
            AgentSubAgent subAgentAnn = nested.getAnnotation(AgentSubAgent.class);
            if (skillAnn != null) {
                caps.add(nestedSkillCapability(ann.id(), skillAnn));
            } else if (subAgentAnn != null) {
                caps.add(nestedSubAgentCapability(ann.id(), subAgentAnn, nested));
            }
        }

        if (caps.isEmpty()) {
            log.warn("@Plugin '{}' has no @AgentTool methods or nested @AgentSkill/@AgentSubAgent classes — skipped", ann.id());
            return null;
        }

        log.info("Loaded plugin '{}' from class {} with {} capabilities", ann.id(), clazz.getSimpleName(), caps.size());
        return PluginDescriptor.builder()
                .pluginId(ann.id())
                .version(ann.version())
                .name(ann.name().isEmpty() ? ann.id() : ann.name())
                .description(ann.description())
                .tags(Arrays.asList(ann.tags()))
                .capabilities(caps)
                .build();
    }

    private CapabilityDescriptor nestedSkillCapability(String pluginId, AgentSkill ann) {
        return CapabilityDescriptor.builder()
                .capabilityId(pluginId + "." + ann.id())
                .pluginId(pluginId)
                .type(CapabilityType.SKILL)
                .tags(Arrays.asList(ann.tags()))
                .skillConfig(buildSkillConfig(ann))
                .build();
    }

    private CapabilityDescriptor nestedSubAgentCapability(String pluginId, AgentSubAgent ann, Class<?> nestedClazz) {
        List<Tool> ownTools = ToolScanner.scan(instantiateNested(nestedClazz));
        return CapabilityDescriptor.builder()
                .capabilityId(pluginId + "." + ann.id())
                .pluginId(pluginId)
                .type(CapabilityType.SUB_AGENT)
                .tags(Arrays.asList(ann.tags()))
                .subAgentConfig(buildSubAgentConfig(ann, ownTools))
                .build();
    }

    private Object instantiateNested(Class<?> nestedClazz) {
        try {
            return nestedClazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to instantiate nested class '" + nestedClazz.getName()
                    + "' — it must be a public static class with a public no-arg constructor", e);
        }
    }

    /** A Skill never owns tools, so the annotated class needs no {@code @AgentTool} methods. */
    private PluginDescriptor loadSkillBean(AgentSkill ann, Object instance) {
        SkillConfig config = buildSkillConfig(ann);

        CapabilityDescriptor cap = CapabilityDescriptor.builder()
                .capabilityId(ann.id())
                .pluginId(ann.id())
                .type(CapabilityType.SKILL)
                .tags(Arrays.asList(ann.tags()))
                .skillConfig(config)
                .build();

        log.info("Loaded skill '{}' from class {}", ann.id(), instance.getClass().getSimpleName());
        return PluginDescriptor.builder()
                .pluginId(ann.id())
                .name(config.getName())
                .description(ann.description())
                .tags(Arrays.asList(ann.tags()))
                .capabilities(List.of(cap))
                .build();
    }

    private SkillConfig buildSkillConfig(AgentSkill ann) {
        return SkillConfig.builder()
                .name(ann.name().isEmpty() ? ann.id() : ann.name())
                .description(ann.description())
                .systemPrompt(ann.systemPrompt())
                .allowedTools(Arrays.asList(ann.allowedTools()))
                .maxIterations(ann.maxIterations() >= 0 ? ann.maxIterations() : null)
                .build();
    }

    /** Any {@code @AgentTool} method on the annotated class becomes a private ownTool. */
    private PluginDescriptor loadSubAgentBean(AgentSubAgent ann, Object instance) {
        List<Tool> ownTools = ToolScanner.scan(instance);
        SubAgentConfig config = buildSubAgentConfig(ann, ownTools);

        CapabilityDescriptor cap = CapabilityDescriptor.builder()
                .capabilityId(ann.id())
                .pluginId(ann.id())
                .type(CapabilityType.SUB_AGENT)
                .tags(Arrays.asList(ann.tags()))
                .subAgentConfig(config)
                .build();

        log.info("Loaded subagent '{}' from class {} with {} own tool(s)",
                ann.id(), instance.getClass().getSimpleName(), ownTools.size());
        return PluginDescriptor.builder()
                .pluginId(ann.id())
                .name(config.getName())
                .description(ann.description())
                .tags(Arrays.asList(ann.tags()))
                .capabilities(List.of(cap))
                .build();
    }

    private SubAgentConfig buildSubAgentConfig(AgentSubAgent ann, List<Tool> ownTools) {
        return SubAgentConfig.builder()
                .name(ann.name().isEmpty() ? ann.id() : ann.name())
                .description(ann.description())
                .model(ann.model())
                .systemPrompt(ann.systemPrompt())
                .allowedTools(Arrays.asList(ann.allowedTools()))
                .ownTools(ownTools.isEmpty() ? null : ownTools)
                .maxIterations(ann.maxIterations() >= 0 ? ann.maxIterations() : null)
                .build();
    }
}
