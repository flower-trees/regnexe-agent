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

package org.salt.regnexeagent.core.market;

import lombok.extern.slf4j.Slf4j;
import org.salt.regnexeagent.core.common.enums.CapabilityType;
import org.salt.regnexeagent.core.market.plugin.CapabilityDescriptor;
import org.salt.regnexeagent.core.market.plugin.Plugin;
import org.salt.regnexeagent.core.market.plugin.PluginDescriptor;
import org.salt.jlangchain.core.skill.ScriptDef;
import org.salt.jlangchain.core.skill.ScriptTool;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.skill.loader.FileSystemSkillConfigLoader;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.core.subagent.loader.FileSystemSubAgentConfigLoader;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.rag.tools.annotation.ToolScanner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Default implementation of {@link PluginManager}.
 *
 * <p>Accumulates sources via fluent methods, then installs everything into a
 * {@link Marketplace} when {@link #installTo} is called (typically via
 * {@link Marketplace#load(PluginManager)}).
 *
 * <p>No LLM or ChainActor dependency: SKILL and SUB_AGENT capabilities store their
 * raw config ({@link SkillConfig} / {@link SubAgentConfig}) in the descriptor.
 * The actual Skill/SubAgent objects are built lazily by
 * {@link org.salt.regnexeagent.core.task.worker.CapabilityExecutor} at execution time.
 *
 * <pre>
 * marketplace.load(
 *     new DefaultPluginManager()
 *         .addDirectory("/opt/regnexe-plugins")
 *         .scanPackages("com.example.plugins")
 *         .register(weatherBean)
 * );
 * </pre>
 *
 * <p>Directory layout for {@link #addDirectory}:
 * <pre>
 * {baseDir}/
 *   my-plugin/
 *     plugin.yaml          ← pluginId, name, description, tags, version
 *     tools/
 *       get_weather.groovy ← script tool (subprocess)
 *       get_weather.yaml   ← sidecar: description, params, tags
 *     skills/
 *       travel_advisor/    ← SKILL.md layout
 *     subagents/
 *       data_analyst/      ← AGENT.md layout
 * </pre>
 */
@Slf4j
public class DefaultPluginManager implements PluginManager {

    private final List<String> directories = new ArrayList<>();
    private final List<String> packages    = new ArrayList<>();
    private final List<Object> beans       = new ArrayList<>();

    // ── fluent configuration ──────────────────────────────────────────────────

    public DefaultPluginManager addDirectory(String dir) {
        directories.add(dir);
        return this;
    }

    public DefaultPluginManager register(Object pluginBean) {
        beans.add(pluginBean);
        return this;
    }

    // ── PluginManager interface ───────────────────────────────────────────────

    @Override
    public void loadFromDirectory(String directoryPath) {
        directories.add(directoryPath);
    }

    @Override
    public void scanPackages(String... basePackages) {
        packages.addAll(Arrays.asList(basePackages));
    }

    @Override
    public void installTo(Marketplace marketplace) {
        directories.forEach(dir -> loadDirectory(dir, marketplace));
        packages.forEach(pkg -> scanPackage(pkg, marketplace));
        beans.forEach(bean -> registerBean(bean, marketplace));
    }

    // ── directory loading ─────────────────────────────────────────────────────

    private void loadDirectory(String baseDir, Marketplace marketplace) {
        Path base = Path.of(baseDir);
        if (!Files.isDirectory(base)) {
            log.warn("Plugin directory not found: {}", baseDir);
            return;
        }
        try (Stream<Path> subdirs = Files.list(base)) {
            subdirs.filter(Files::isDirectory).sorted()
                   .forEach(pluginDir -> loadPlugin(pluginDir, marketplace));
        } catch (IOException e) {
            log.error("Failed to scan plugin directory: {}", baseDir, e);
        }
    }

    private void loadPlugin(Path pluginDir, Marketplace marketplace) {
        Path manifestPath = pluginDir.resolve("plugin.yaml");
        if (!Files.exists(manifestPath)) {
            log.debug("Skipping directory (no plugin.yaml): {}", pluginDir);
            return;
        }

        Map<String, Object> manifest = parseYaml(manifestPath);
        String pluginId  = getString(manifest, "pluginId",     pluginDir.getFileName().toString());
        String name      = getString(manifest, "name",         pluginId);
        String version   = getString(manifest, "version",      "1.0");
        String desc      = getString(manifest, "description",  "");
        List<String> tags = getStringList(manifest, "tags");

        List<CapabilityDescriptor> caps = new ArrayList<>();
        caps.addAll(loadTools(pluginId, pluginDir));
        caps.addAll(loadSkills(pluginId, pluginDir));
        caps.addAll(loadSubAgents(pluginId, pluginDir));

        if (caps.isEmpty()) {
            log.warn("Plugin '{}' has no loadable capabilities — skipped", pluginId);
            return;
        }

        marketplace.install(PluginDescriptor.builder()
                .pluginId(pluginId).version(version)
                .name(name).description(desc).tags(tags)
                .capabilities(caps)
                .build());
        log.info("Installed plugin '{}' with {} capabilities", pluginId, caps.size());
    }

    private List<CapabilityDescriptor> loadTools(String pluginId, Path pluginDir) {
        Path toolsDir = pluginDir.resolve("tools");
        if (!Files.isDirectory(toolsDir)) return List.of();

        List<CapabilityDescriptor> caps = new ArrayList<>();
        try (Stream<Path> files = Files.list(toolsDir)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> !isYaml(p))
                 .sorted()
                 .forEach(scriptFile -> {
                     String filename = scriptFile.getFileName().toString();
                     if (!filename.contains(".")) return;
                     String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
                     if (!ScriptTool.supports(ext)) {
                         log.debug("Unsupported script type, skipping: {}", filename);
                         return;
                     }
                     String toolName = filename.substring(0, filename.lastIndexOf('.'));

                     Path sidecar = toolsDir.resolve(toolName + ".yaml");
                     Map<String, Object> meta = Files.exists(sidecar) ? parseYaml(sidecar) : Map.of();
                     String toolDesc   = getString(meta, "description", "Execute script: " + toolName);
                     String toolParams = getString(meta, "params",      "args: String");
                     List<String> toolTags = getStringList(meta, "tags");

                     try {
                         String content = Files.readString(scriptFile);
                         ScriptDef def = ScriptDef.builder().name(toolName).type(ext).content(content).build();
                         Tool base = ScriptTool.from(def);
                         Tool tool = Tool.builder()
                                 .name(toolName).description(toolDesc).params(toolParams)
                                 .func(base.getFunc())
                                 .build();
                         caps.add(CapabilityDescriptor.builder()
                                 .capabilityId(pluginId + "." + toolName)
                                 .pluginId(pluginId).type(CapabilityType.MCP_TOOL)
                                 .name(toolName).description(toolDesc).tags(toolTags)
                                 .tool(tool)
                                 .build());
                         log.debug("Loaded tool '{}' from plugin '{}'", toolName, pluginId);
                     } catch (Exception e) {
                         log.warn("Failed to load tool '{}' in plugin '{}': {}", toolName, pluginId, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.debug("No tools/ in plugin '{}'", pluginId);
        }
        return caps;
    }

    private List<CapabilityDescriptor> loadSkills(String pluginId, Path pluginDir) {
        Path skillsDir = pluginDir.resolve("skills");
        if (!Files.isDirectory(skillsDir)) return List.of();

        List<CapabilityDescriptor> caps = new ArrayList<>();
        try (Stream<Path> subdirs = Files.list(skillsDir)) {
            subdirs.filter(Files::isDirectory).sorted().forEach(skillDir -> {
                try {
                    SkillConfig config = FileSystemSkillConfigLoader.fromPath(skillDir);
                    caps.add(CapabilityDescriptor.builder()
                            .capabilityId(pluginId + "." + config.getName())
                            .pluginId(pluginId).type(CapabilityType.SKILL)
                            .name(config.getName()).description(config.getDescription())
                            .skillConfig(config)
                            .build());
                    log.debug("Loaded skill '{}' from plugin '{}'", config.getName(), pluginId);
                } catch (Exception e) {
                    log.warn("Failed to load skill '{}' in plugin '{}': {}", skillDir.getFileName(), pluginId, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("No skills/ in plugin '{}'", pluginId);
        }
        return caps;
    }

    private List<CapabilityDescriptor> loadSubAgents(String pluginId, Path pluginDir) {
        Path agentsDir = pluginDir.resolve("subagents");
        if (!Files.isDirectory(agentsDir)) return List.of();

        List<CapabilityDescriptor> caps = new ArrayList<>();
        try (Stream<Path> subdirs = Files.list(agentsDir)) {
            subdirs.filter(Files::isDirectory).sorted().forEach(agentDir -> {
                try {
                    SubAgentConfig config = FileSystemSubAgentConfigLoader.fromPath(agentDir);
                    caps.add(CapabilityDescriptor.builder()
                            .capabilityId(pluginId + "." + config.getName())
                            .pluginId(pluginId).type(CapabilityType.SUB_AGENT)
                            .name(config.getName()).description(config.getDescription())
                            .subAgentConfig(config)
                            .build());
                    log.debug("Loaded subagent '{}' from plugin '{}'", config.getName(), pluginId);
                } catch (Exception e) {
                    log.warn("Failed to load subagent '{}' in plugin '{}': {}", agentDir.getFileName(), pluginId, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("No subagents/ in plugin '{}'", pluginId);
        }
        return caps;
    }

    // ── annotation scanning ───────────────────────────────────────────────────

    private void scanPackage(String pkg, Marketplace marketplace) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Plugin.class));

        scanner.findCandidateComponents(pkg).forEach(bd -> {
            try {
                Class<?> clazz = Class.forName(bd.getBeanClassName());
                Object instance = clazz.getDeclaredConstructor().newInstance();
                registerPluginBean(clazz.getAnnotation(Plugin.class), instance, marketplace);
            } catch (Exception e) {
                log.warn("Failed to register plugin class '{}': {}", bd.getBeanClassName(), e.getMessage());
            }
        });
    }

    private void registerBean(Object pluginBean, Marketplace marketplace) {
        Class<?> clazz = pluginBean.getClass();
        Plugin ann = clazz.getAnnotation(Plugin.class);
        if (ann == null) {
            log.warn("register() called on object without @Plugin: {}", clazz.getName());
            return;
        }
        registerPluginBean(ann, pluginBean, marketplace);
    }

    private void registerPluginBean(Plugin ann, Object instance, Marketplace marketplace) {
        List<Tool> tools = ToolScanner.scan(instance);
        if (tools.isEmpty()) {
            log.warn("@Plugin '{}' has no @AgentTool methods — skipped", ann.id());
            return;
        }

        List<CapabilityDescriptor> caps = tools.stream()
                .map(tool -> CapabilityDescriptor.builder()
                        .capabilityId(ann.id() + "." + tool.getName())
                        .pluginId(ann.id())
                        .type(CapabilityType.MCP_TOOL)
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .tool(tool)
                        .build())
                .toList();

        marketplace.install(PluginDescriptor.builder()
                .pluginId(ann.id())
                .version(ann.version())
                .name(ann.name().isEmpty() ? ann.id() : ann.name())
                .description(ann.description())
                .tags(Arrays.asList(ann.tags()))
                .capabilities(caps)
                .build());
        log.info("Registered plugin '{}' from class {}", ann.id(), instance.getClass().getSimpleName());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseYaml(Path path) {
        try {
            Map<String, Object> result = new Yaml().load(Files.readString(path));
            return result != null ? result : Map.of();
        } catch (Exception e) {
            log.warn("Failed to parse YAML '{}': {}", path, e.getMessage());
            return Map.of();
        }
    }

    private String getString(Map<String, Object> map, String key, String defaultVal) {
        Object val = map.get(key);
        return (val != null && !val.toString().isBlank()) ? val.toString() : defaultVal;
    }

    private List<String> getStringList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return List.of();
        if (val instanceof List<?> list) return list.stream().map(Object::toString).toList();
        if (val instanceof String str) {
            return Arrays.stream(str.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
        }
        return List.of();
    }

    private boolean isYaml(Path p) {
        String name = p.getFileName().toString();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }
}
