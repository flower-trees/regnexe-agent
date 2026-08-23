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
import org.salt.regnexe.agent.core.marketplace.Marketplace;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityType;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginManifest;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.regnexe.agent.core.task.worker.CapabilityExecutor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Default implementation of {@link PluginManager}.
 *
 * <p>Accumulates sources via fluent methods, then installs everything into a
 * {@link Marketplace} when {@link #installTo} is called (typically via
 * {@link Marketplace#load(PluginManager)}). Discovery itself is delegated to three
 * single-purpose loaders, kept as separate classes so each on-disk convention can evolve
 * independently:
 * <ul>
 *   <li>{@link ManifestPluginLoader} — {@link #addDirectory} — manifest-based plugin bundles
 *       ({@code plugin.yaml} / {@code .claude-plugin/plugin.json} + {@code tools/}/{@code skills/}/
 *       {@code subagents/}); {@link #addPluginDirectory} — same loader, but for a single
 *       already-resolved plugin directory (e.g. a {@code cache/<plugin-id>/<hash>/} path resolved
 *       by {@code PluginCacheInstaller}) instead of a parent containing many plugin subdirectories</li>
 *   <li>{@link FlatSkillLoader} — {@link #addSkillsDirectory} — manifest-less, directly-editable
 *       skills ({@code skills/<name>/SKILL.md}, no nesting)</li>
 *   <li>{@link AnnotationPluginLoader} — {@link #scanPackages} / {@link #register} —
 *       {@code @Plugin}/{@code @AgentSkill}/{@code @AgentSubAgent} annotated classes</li>
 * </ul>
 *
 * <p>No LLM or ChainActor dependency: SKILL and SUB_AGENT capabilities store their
 * raw config ({@link SkillConfig} / {@link SubAgentConfig}) in the descriptor.
 * The actual Skill/SubAgent objects are built lazily by
 * {@link CapabilityExecutor} at execution time.
 *
 * <pre>
 * marketplace.load(
 *     new DefaultPluginManager()
 *         .addDirectory("/opt/regnexe-plugins/marketplaces/default/plugins")
 *         .addSkillsDirectory("/opt/regnexe-plugins/skills")
 *         .scanPackages("com.example.plugins")
 *         .register(weatherBean)
 * );
 * </pre>
 */
@Slf4j
public class DefaultPluginManager implements PluginManager {

    private final ManifestPluginLoader manifestLoader = new ManifestPluginLoader();
    private final FlatSkillLoader flatSkillLoader = new FlatSkillLoader();
    private final AnnotationPluginLoader annotationLoader = new AnnotationPluginLoader();

    private final List<String> directories           = new ArrayList<>();
    private final List<String> resolvedPluginDirectories = new ArrayList<>();
    private final List<String> skillsDirectories      = new ArrayList<>();
    private final List<String> packages              = new ArrayList<>();
    private final List<Object> beans                 = new ArrayList<>();
    private final List<Tool> tools                   = new ArrayList<>();
    private final List<SkillConfig> skillConfigs     = new ArrayList<>();
    private final List<SubAgentConfig> subAgentConfigs = new ArrayList<>();

    // ── fluent configuration ──────────────────────────────────────────────────

    /** Accumulate a base directory of manifest-based plugin bundles (see {@link ManifestPluginLoader}). */
    public DefaultPluginManager addDirectory(String dir) {
        directories.add(dir);
        return this;
    }

    /**
     * Accumulate a single, already-resolved plugin directory — the directory itself carries the
     * manifest, unlike {@link #addDirectory} which expects a parent containing many plugin
     * subdirectories. Used for {@code cache/<plugin-id>/<hash>/} paths.
     */
    public DefaultPluginManager addPluginDirectory(String dir) {
        resolvedPluginDirectories.add(dir);
        return this;
    }

    /** Accumulate a base directory of manifest-less, directly-editable skills (see {@link FlatSkillLoader}). */
    public DefaultPluginManager addSkillsDirectory(String dir) {
        skillsDirectories.add(dir);
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
    public void loadPluginDirectory(String pluginDirectoryPath) {
        resolvedPluginDirectories.add(pluginDirectoryPath);
    }

    @Override
    public void loadSkillsFromDirectory(String directoryPath) {
        skillsDirectories.add(directoryPath);
    }

    @Override
    public void scanPackages(String... basePackages) {
        packages.addAll(Arrays.asList(basePackages));
    }

    @Override
    public void installTo(Marketplace marketplace) {
        directories.forEach(dir -> loadManifestDirectory(dir, marketplace));
        resolvedPluginDirectories.forEach(dir -> loadSinglePluginDirectory(dir, marketplace));
        skillsDirectories.forEach(dir -> flatSkillLoader.loadAll(Path.of(dir))
                .forEach(descriptor -> installCatching(descriptor, marketplace)));
        packages.forEach(pkg -> annotationLoader.scanPackages(pkg)
                .forEach(descriptor -> installCatching(descriptor, marketplace)));
        beans.forEach(bean -> {
            PluginDescriptor descriptor = annotationLoader.load(bean);
            if (descriptor != null) installCatching(descriptor, marketplace);
        });
        tools.forEach(tool -> registerToolCapability(tool, marketplace));
        skillConfigs.forEach(config -> registerSkillCapability(config, marketplace));
        subAgentConfigs.forEach(config -> registerSubAgentCapability(config, marketplace));
    }

    // ── manifest-based directory loading ───────────────────────────────────────

    private void loadManifestDirectory(String baseDir, Marketplace marketplace) {
        Path base = Path.of(baseDir);
        if (!Files.isDirectory(base)) {
            log.warn("Plugin directory not found: {}", baseDir);
            return;
        }
        try (Stream<Path> subdirs = Files.list(base)) {
            subdirs.filter(Files::isDirectory).sorted().forEach(pluginDir -> {
                PluginManifest manifest = manifestLoader.loadManifest(pluginDir);
                if (manifest == null) {
                    log.debug("Skipping directory (no plugin.yaml / .claude-plugin/plugin.json): {}", pluginDir);
                    return;
                }
                PluginDescriptor descriptor = manifestLoader.load(pluginDir, manifest);
                if (descriptor != null) installCatching(descriptor, marketplace);
            });
        } catch (IOException e) {
            log.error("Failed to scan plugin directory: {}", baseDir, e);
        }
    }

    /** {@code dirPath} itself carries the manifest — no subdirectory enumeration, unlike {@link #loadManifestDirectory}. */
    private void loadSinglePluginDirectory(String dirPath, Marketplace marketplace) {
        Path pluginDir = Path.of(dirPath);
        if (!Files.isDirectory(pluginDir)) {
            log.warn("Resolved plugin directory not found: {}", dirPath);
            return;
        }
        PluginManifest manifest = manifestLoader.loadManifest(pluginDir);
        if (manifest == null) {
            log.warn("Resolved plugin directory has no manifest, skipping: {}", dirPath);
            return;
        }
        PluginDescriptor descriptor = manifestLoader.load(pluginDir, manifest);
        if (descriptor != null) installCatching(descriptor, marketplace);
    }

    /**
     * Directories are scanned in the caller-supplied order, so the first plugin/capability id
     * registered wins — e.g. {@code addDirectory(projectDir).addDirectory(userDir)} gives
     * project-level plugins priority over identically-named user-level ones instead of crashing
     * the whole load.
     */
    private void installCatching(PluginDescriptor descriptor, Marketplace marketplace) {
        try {
            marketplace.install(descriptor);
            log.info("Installed plugin '{}' with {} capabilities", descriptor.getPluginId(),
                    descriptor.getCapabilities().size());
        } catch (IllegalStateException e) {
            log.warn("Skipping plugin '{}': {}", descriptor.getPluginId(), e.getMessage());
        }
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
}
