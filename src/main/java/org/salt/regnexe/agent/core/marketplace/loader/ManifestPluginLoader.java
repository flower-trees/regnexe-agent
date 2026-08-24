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
import org.salt.regnexe.agent.core.marketplace.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginManifest;
import org.salt.jlangchain.core.skill.ScriptDef;
import org.salt.jlangchain.core.skill.ScriptTool;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.skill.loader.FileSystemSkillConfigLoader;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.core.subagent.loader.FileSystemSubAgentConfigLoader;
import org.salt.jlangchain.rag.tools.Tool;
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
 * Loads a single manifest-based Plugin from a directory:
 * <pre>
 * {pluginDir}/
 *   plugin.yaml          ← pluginId, name, description, tags, version
 *   .claude-plugin/
 *     plugin.json         ← Claude Code compat manifest, used when plugin.yaml is absent
 *   tools/
 *     get_weather.groovy ← script tool (subprocess)
 *     get_weather.yaml   ← sidecar: description, params, tags
 *   skills/
 *     travel_advisor/    ← SKILL.md layout
 *   subagents/
 *     data_analyst/      ← AGENT.md layout
 * </pre>
 *
 * <p>Directories with no manifest are the caller's problem to skip — see
 * {@link FlatSkillLoader} for the separate, manifest-less {@code skills/} convention.
 */
@Slf4j
public class ManifestPluginLoader {

    /**
     * Manifest lookup order: {@code plugin.yaml} (regnexe-native) takes priority over
     * {@code .claude-plugin/plugin.json} (Claude Code plugin manifest). Returns null when
     * neither is present, so the caller decides whether that's an error or a skip.
     *
     * <p>plugin.json is valid YAML (YAML is a JSON superset), so it's parsed with the same
     * SnakeYAML reader as plugin.yaml — no extra JSON dependency needed.
     */
    public PluginManifest loadManifest(Path pluginDir) {
        Path nativeManifest = pluginDir.resolve("plugin.yaml");
        if (Files.exists(nativeManifest)) {
            return toManifest(parseYaml(nativeManifest));
        }
        Path claudeManifest = pluginDir.resolve(".claude-plugin").resolve("plugin.json");
        if (Files.exists(claudeManifest)) {
            return toManifest(parseYaml(claudeManifest));
        }
        return null;
    }

    private PluginManifest toManifest(Map<String, Object> raw) {
        // Claude Code plugin.json uses "keywords"; our native plugin.yaml uses "tags" — accept either.
        List<String> tags = firstNonEmpty(getStringList(raw, "tags"), getStringList(raw, "keywords"));
        return PluginManifest.builder()
                .pluginId(getString(raw, "pluginId", null))
                .name(getString(raw, "name", null))
                .version(getString(raw, "version", null))
                .description(getString(raw, "description", null))
                .tags(tags)
                .build();
    }

    /**
     * Builds the full {@link PluginDescriptor} for {@code pluginDir}, applying directory-name
     * fallbacks for any manifest field left unset. Returns null (instead of a capability-less
     * descriptor) when {@code tools/}/{@code skills/}/{@code subagents/} yield nothing loadable.
     *
     * <p>Falls back to {@code pluginDir}'s own directory name when the manifest declares no
     * {@code pluginId} — correct for {@code plugins/<plugin-id>/}, where the directory name
     * genuinely is the id. See {@link #load(Path, PluginManifest, String)} for the cache-directory
     * case, where it is not.
     */
    public PluginDescriptor load(Path pluginDir, PluginManifest manifest) {
        return load(pluginDir, manifest, pluginDir.getFileName().toString());
    }

    /**
     * Same as {@link #load(Path, PluginManifest)}, but with an explicit fallback id instead of
     * deriving one from {@code pluginDir}'s own name. Needed for
     * {@code cache/<plugin-id>/<hash>/} directories (see {@code PluginCacheInstaller} /
     * {@code DefaultPluginManager#loadSinglePluginDirectory}) — the hash-named leaf directory is
     * not a meaningful plugin id, so callers there pass the parent directory's name (the real
     * plugin id) instead. Using the hash as a silent fallback used to register a plugin under the
     * wrong id whenever its manifest didn't declare {@code pluginId} explicitly (Claude Code's
     * {@code plugin.json} commonly only has {@code name}, not {@code pluginId}) — confirmed via
     * harness-testbed: {@code /plugin disable} silently no-op'd because it was toggling a
     * different id than the one actually registered.
     */
    public PluginDescriptor load(Path pluginDir, PluginManifest manifest, String idFallback) {
        String pluginId = orDefault(manifest.getPluginId(), idFallback);
        String name = orDefault(manifest.getName(), pluginId);
        String version = orDefault(manifest.getVersion(), "1.0");
        String description = orDefault(manifest.getDescription(), "");
        List<String> tags = manifest.getTags() != null ? manifest.getTags() : List.of();

        List<CapabilityDescriptor> caps = new ArrayList<>();
        caps.addAll(loadTools(pluginId, pluginDir));
        caps.addAll(loadSkills(pluginId, pluginDir));
        caps.addAll(loadSubAgents(pluginId, pluginDir));

        if (caps.isEmpty()) {
            log.warn("Plugin '{}' has no loadable capabilities — skipped", pluginId);
            return null;
        }

        return PluginDescriptor.builder()
                .pluginId(pluginId).version(version)
                .name(name).description(description).tags(tags)
                .capabilities(caps)
                .build();
    }

    // ── tools/ skills/ subagents/ ────────────────────────────────────────────

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

    // ── helpers ──────────────────────────────────────────────────────────────

    private String orDefault(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private List<String> firstNonEmpty(List<String> a, List<String> b) {
        return (a == null || a.isEmpty()) ? b : a;
    }

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
