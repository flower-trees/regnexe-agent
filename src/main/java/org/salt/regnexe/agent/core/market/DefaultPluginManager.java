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

package org.salt.regnexe.agent.core.market;

import lombok.extern.slf4j.Slf4j;
import org.salt.regnexe.agent.core.common.enums.CapabilityType;
import org.salt.regnexe.agent.core.market.plugin.AgentSkill;
import org.salt.regnexe.agent.core.market.plugin.AgentSubAgent;
import org.salt.regnexe.agent.core.market.plugin.CapabilityDescriptor;
import org.salt.regnexe.agent.core.market.plugin.Plugin;
import org.salt.regnexe.agent.core.market.plugin.PluginDescriptor;
import org.salt.jlangchain.core.skill.ScriptDef;
import org.salt.jlangchain.core.skill.ScriptTool;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.skill.loader.FileSystemSkillConfigLoader;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.core.subagent.loader.FileSystemSubAgentConfigLoader;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.rag.tools.annotation.ToolScanner;
import org.salt.regnexe.agent.core.task.worker.CapabilityExecutor;
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
 * {@link CapabilityExecutor} at execution time.
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

    private final List<String> directories           = new ArrayList<>();
    private final List<String> packages              = new ArrayList<>();
    private final List<Object> beans                 = new ArrayList<>();
    private final List<Tool> tools                   = new ArrayList<>();
    private final List<SkillConfig> skillConfigs     = new ArrayList<>();
    private final List<SubAgentConfig> subAgentConfigs = new ArrayList<>();

    // ── fluent configuration ──────────────────────────────────────────────────

    public DefaultPluginManager addDirectory(String dir) {
        directories.add(dir);
        return this;
    }

    public DefaultPluginManager register(Object pluginBean) {
        beans.add(pluginBean);
        return this;
    }

    /** Register a pre-built Tool directly, without a {@code @Plugin} wrapper. */
    public DefaultPluginManager registerTool(Tool tool) {
        tools.add(tool);
        return this;
    }

    /** Register a SKILL capability directly from a SkillConfig, without a SKILL.md file. */
    public DefaultPluginManager registerSkill(SkillConfig config) {
        skillConfigs.add(config);
        return this;
    }

    /** Register a SUB_AGENT capability directly from a SubAgentConfig, without an AGENT.md file. */
    public DefaultPluginManager registerSubAgent(SubAgentConfig config) {
        subAgentConfigs.add(config);
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
        tools.forEach(tool -> registerToolCapability(tool, marketplace));
        skillConfigs.forEach(config -> registerSkillCapability(config, marketplace));
        subAgentConfigs.forEach(config -> registerSubAgentCapability(config, marketplace));
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
        Map<String, Object> manifest = loadManifest(pluginDir);
        if (manifest == null) {
            loadFlatSkillPlugin(pluginDir, marketplace);
            return;
        }

        String pluginId  = getString(manifest, "pluginId",     pluginDir.getFileName().toString());
        String name      = getString(manifest, "name",         pluginId);
        String version   = getString(manifest, "version",      "1.0");
        String desc      = getString(manifest, "description",  "");
        // Claude Code plugin.json uses "keywords"; our native plugin.yaml uses "tags" — accept either.
        List<String> tags = firstNonEmpty(getStringList(manifest, "tags"), getStringList(manifest, "keywords"));

        List<CapabilityDescriptor> caps = new ArrayList<>();
        caps.addAll(loadTools(pluginId, pluginDir));
        caps.addAll(loadSkills(pluginId, pluginDir));
        caps.addAll(loadSubAgents(pluginId, pluginDir));

        if (caps.isEmpty()) {
            log.warn("Plugin '{}' has no loadable capabilities — skipped", pluginId);
            return;
        }

        try {
            marketplace.install(PluginDescriptor.builder()
                    .pluginId(pluginId).version(version)
                    .name(name).description(desc).tags(tags)
                    .capabilities(caps)
                    .build());
            log.info("Installed plugin '{}' with {} capabilities", pluginId, caps.size());
        } catch (IllegalStateException e) {
            // Directories are scanned in the caller-supplied order, so the first plugin/capability
            // id registered wins — e.g. addDirectory(projectDir, userDir) gives project-level plugins
            // priority over identically-named user-level ones instead of crashing the whole load.
            log.warn("Skipping plugin '{}' from {}: {}", pluginId, pluginDir, e.getMessage());
        }
    }

    /**
     * Fallback for a scanned directory with no plugin.yaml/.claude-plugin manifest: if it
     * directly contains a SKILL.md, treat the whole directory as a single-skill plugin
     * (pluginId = directory name), no manifest required.
     *
     * <p>This is Claude Code's flat "personal/project skill" layout ({@code .claude/skills/<name>/
     * SKILL.md}) — distinct from the nested marketplace-plugin layout ({@code .claude-plugin/
     * plugin.json} + {@code skills/<name>/SKILL.md}) the manifest branch handles. It's also
     * exactly what skill-creator itself produces when asked to create a standalone skill
     * (it writes {@code <name>/SKILL.md} directly, no {@code skills/} nesting, no manifest) —
     * without this fallback, anything skill-creator builds is invisible to the marketplace scan.
     */
    private void loadFlatSkillPlugin(Path pluginDir, Marketplace marketplace) {
        if (!Files.exists(pluginDir.resolve("SKILL.md"))) {
            log.debug("Skipping directory (no manifest, no SKILL.md): {}", pluginDir);
            return;
        }
        String pluginId = pluginDir.getFileName().toString();
        try {
            SkillConfig config = FileSystemSkillConfigLoader.fromPath(pluginDir);
            CapabilityDescriptor cap = CapabilityDescriptor.builder()
                    .capabilityId(pluginId + "." + config.getName())
                    .pluginId(pluginId).type(CapabilityType.SKILL)
                    .skillConfig(config)
                    .build();
            marketplace.install(PluginDescriptor.builder()
                    .pluginId(pluginId).version("1.0")
                    .name(config.getName()).description(config.getDescription())
                    .tags(List.of())
                    .capabilities(List.of(cap))
                    .build());
            log.info("Installed flat skill '{}' from {}", config.getName(), pluginDir);
        } catch (IllegalStateException e) {
            log.warn("Skipping flat skill '{}' from {}: {}", pluginId, pluginDir, e.getMessage());
        } catch (Exception e) {
            log.warn("Failed to load flat skill from {}: {}", pluginDir, e.getMessage());
        }
    }

    /**
     * Manifest lookup order: {@code plugin.yaml} (regnexe-native) takes priority over
     * {@code .claude-plugin/plugin.json} (Claude Code plugin manifest). Returns null when
     * neither is present, so the caller skips the directory.
     *
     * <p>plugin.json is valid YAML (YAML is a JSON superset), so it's parsed with the same
     * SnakeYAML reader as plugin.yaml — no extra JSON dependency needed.
     */
    private Map<String, Object> loadManifest(Path pluginDir) {
        Path nativeManifest = pluginDir.resolve("plugin.yaml");
        if (Files.exists(nativeManifest)) {
            return parseYaml(nativeManifest);
        }
        Path claudeManifest = pluginDir.resolve(".claude-plugin").resolve("plugin.json");
        if (Files.exists(claudeManifest)) {
            return parseYaml(claudeManifest);
        }
        return null;
    }

    private List<String> firstNonEmpty(List<String> a, List<String> b) {
        return (a == null || a.isEmpty()) ? b : a;
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
                         if (!ScriptTool.hasEntrypoint(ext, content)) {
                             log.debug("Skipping library-only script (no entrypoint): {}", filename);
                             return;
                         }
                         // sourcePath/workDir: run in place (plugin root as cwd) instead of an
                         // isolated temp copy, so relative imports/sibling-file lookups resolve
                         // — same fix as FileSystemSkillConfigLoader.loadScripts() (j-langchain).
                         ScriptDef def = ScriptDef.builder().name(toolName).type(ext).content(content)
                                 .sourcePath(scriptFile.toAbsolutePath().toString())
                                 .workDir(pluginDir.toAbsolutePath().toString())
                                 .build();
                         Tool base = ScriptTool.from(def);
                         Tool tool = Tool.builder()
                                 .name(toolName).description(toolDesc).params(toolParams)
                                 .func(base.getFunc())
                                 .build();
                         caps.add(CapabilityDescriptor.builder()
                                 .capabilityId(pluginId + "." + toolName)
                                 .pluginId(pluginId).type(CapabilityType.MCP_TOOL)
                                 .tags(toolTags)
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
        scanner.addIncludeFilter(new AnnotationTypeFilter(AgentSkill.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(AgentSubAgent.class));

        scanner.findCandidateComponents(pkg).forEach(bd -> {
            try {
                Class<?> clazz = Class.forName(bd.getBeanClassName());
                Object instance = clazz.getDeclaredConstructor().newInstance();
                registerAnnotatedBean(clazz, instance, marketplace);
            } catch (Exception e) {
                log.warn("Failed to register annotated class '{}': {}", bd.getBeanClassName(), e.getMessage());
            }
        });
    }

    private void registerBean(Object bean, Marketplace marketplace) {
        registerAnnotatedBean(bean.getClass(), bean, marketplace);
    }

    /** Dispatches to the registration logic matching whichever capability annotation is present. */
    private void registerAnnotatedBean(Class<?> clazz, Object instance, Marketplace marketplace) {
        Plugin pluginAnn = clazz.getAnnotation(Plugin.class);
        AgentSkill skillAnn = clazz.getAnnotation(AgentSkill.class);
        AgentSubAgent subAgentAnn = clazz.getAnnotation(AgentSubAgent.class);

        if (pluginAnn != null) {
            registerPluginBean(pluginAnn, instance, marketplace);
        } else if (skillAnn != null) {
            registerSkillBean(skillAnn, instance, marketplace);
        } else if (subAgentAnn != null) {
            registerSubAgentBean(subAgentAnn, instance, marketplace);
        } else {
            log.warn("register() called on object without @Plugin/@AgentSkill/@AgentSubAgent: {}", clazz.getName());
        }
    }

    /**
     * Scans {@code @AgentTool} methods (-> MCP_TOOL) and, additionally, any nested static class
     * annotated {@code @AgentSkill}/{@code @AgentSubAgent} (-> SKILL/SUB_AGENT) — all bundled
     * under this plugin's single {@code pluginId}, exactly like the file-based
     * {@code tools/}+{@code skills/}+{@code subagents/} layout or
     * {@code PluginDescriptor.builder().tool().skillConfig().subAgentConfig()}.
     */
    private void registerPluginBean(Plugin ann, Object instance, Marketplace marketplace) {
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
            return;
        }

        marketplace.install(PluginDescriptor.builder()
                .pluginId(ann.id())
                .version(ann.version())
                .name(ann.name().isEmpty() ? ann.id() : ann.name())
                .description(ann.description())
                .tags(Arrays.asList(ann.tags()))
                .capabilities(caps)
                .build());
        log.info("Registered plugin '{}' from class {} with {} capabilities", ann.id(), clazz.getSimpleName(), caps.size());
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
    private void registerSkillBean(AgentSkill ann, Object instance, Marketplace marketplace) {
        SkillConfig config = buildSkillConfig(ann);

        CapabilityDescriptor cap = CapabilityDescriptor.builder()
                .capabilityId(ann.id())
                .pluginId(ann.id())
                .type(CapabilityType.SKILL)
                .tags(Arrays.asList(ann.tags()))
                .skillConfig(config)
                .build();

        marketplace.install(PluginDescriptor.builder()
                .pluginId(ann.id())
                .name(config.getName())
                .description(ann.description())
                .tags(Arrays.asList(ann.tags()))
                .capabilities(List.of(cap))
                .build());
        log.info("Registered skill '{}' from class {}", ann.id(), instance.getClass().getSimpleName());
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
    private void registerSubAgentBean(AgentSubAgent ann, Object instance, Marketplace marketplace) {
        List<Tool> ownTools = ToolScanner.scan(instance);
        SubAgentConfig config = buildSubAgentConfig(ann, ownTools);

        CapabilityDescriptor cap = CapabilityDescriptor.builder()
                .capabilityId(ann.id())
                .pluginId(ann.id())
                .type(CapabilityType.SUB_AGENT)
                .tags(Arrays.asList(ann.tags()))
                .subAgentConfig(config)
                .build();

        marketplace.install(PluginDescriptor.builder()
                .pluginId(ann.id())
                .name(config.getName())
                .description(ann.description())
                .tags(Arrays.asList(ann.tags()))
                .capabilities(List.of(cap))
                .build());
        log.info("Registered subagent '{}' from class {} with {} own tool(s)",
                ann.id(), instance.getClass().getSimpleName(), ownTools.size());
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

    // ── code-first capability registration ────────────────────────────────────

    /**
     * capabilityId/pluginId default to the config's own {@code name} — each code-first
     * registration is treated as its own single-capability plugin, mirroring how
     * file-based plugins derive ids from {@code pluginId + "." + name}.
     */
    private void registerToolCapability(Tool tool, Marketplace marketplace) {
        String name = requireName(tool.getName(), "Tool");
        CapabilityDescriptor cap = CapabilityDescriptor.builder()
                .capabilityId(name).pluginId(name)
                .type(CapabilityType.MCP_TOOL)
                .tool(tool)
                .build();
        installSingle(marketplace, name, tool.getDescription(), cap);
        log.info("Registered tool '{}'", name);
    }

    private void registerSkillCapability(SkillConfig config, Marketplace marketplace) {
        String name = requireName(config.getName(), "SkillConfig");
        CapabilityDescriptor cap = CapabilityDescriptor.builder()
                .capabilityId(name).pluginId(name)
                .type(CapabilityType.SKILL)
                .skillConfig(config)
                .build();
        installSingle(marketplace, name, config.getDescription(), cap);
        log.info("Registered skill '{}'", name);
    }

    private void registerSubAgentCapability(SubAgentConfig config, Marketplace marketplace) {
        String name = requireName(config.getName(), "SubAgentConfig");
        CapabilityDescriptor cap = CapabilityDescriptor.builder()
                .capabilityId(name).pluginId(name)
                .type(CapabilityType.SUB_AGENT)
                .subAgentConfig(config)
                .build();
        installSingle(marketplace, name, config.getDescription(), cap);
        log.info("Registered subagent '{}'", name);
    }

    private void installSingle(Marketplace marketplace, String name, String description, CapabilityDescriptor cap) {
        marketplace.install(PluginDescriptor.builder()
                .pluginId(name).version("1.0")
                .name(name).description(description)
                .tags(List.of())
                .capabilities(List.of(cap))
                .build());
    }

    private String requireName(String name, String kind) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(kind + ".name must not be blank — it becomes the capabilityId.");
        }
        return name;
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
