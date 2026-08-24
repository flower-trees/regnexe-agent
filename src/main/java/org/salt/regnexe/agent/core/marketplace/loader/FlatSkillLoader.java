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
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.skill.loader.FileSystemSkillConfigLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Loads manifest-less, directly-editable skills from a dedicated {@code skills/} root:
 * <pre>
 * skills/
 *   my-skill/
 *     SKILL.md
 *     references/
 *     scripts/
 * </pre>
 *
 * <p>This is Claude Code's "personal/project skill" layout ({@code .claude/skills/<name>/
 * SKILL.md}) — distinct from {@link ManifestPluginLoader}'s marketplace-plugin layout
 * ({@code plugin.yaml} + nested {@code skills/}/{@code tools/}/{@code subagents/}). It's also
 * exactly what skill-creator itself produces when asked to create a standalone skill (it writes
 * {@code <name>/SKILL.md} directly, no manifest, no further nesting).
 *
 * <p>This directory is never installed into a version cache — it's read live, unlike
 * {@link ManifestPluginLoader}'s marketplace plugins, which do go through the install/cache
 * flow.
 *
 * <p>Each subdirectory becomes its own single-capability Plugin, {@code pluginId} = directory
 * name — matching {@code ManifestPluginLoader}'s {@code pluginId + "." + name} capabilityId
 * convention exactly, just without a manifest.
 */
@Slf4j
public class FlatSkillLoader {

    /** Scans every immediate subdirectory of {@code skillsDir} for a bare {@code SKILL.md}. */
    public List<PluginDescriptor> loadAll(Path skillsDir) {
        if (!Files.isDirectory(skillsDir)) {
            log.debug("Skills directory not found: {}", skillsDir);
            return List.of();
        }
        List<PluginDescriptor> result = new ArrayList<>();
        try (Stream<Path> subdirs = Files.list(skillsDir)) {
            subdirs.filter(Files::isDirectory).sorted().forEach(skillDir -> {
                PluginDescriptor descriptor = loadOne(skillDir);
                if (descriptor != null) result.add(descriptor);
            });
        } catch (IOException e) {
            log.error("Failed to scan skills directory: {}", skillsDir, e);
        }
        return result;
    }

    private PluginDescriptor loadOne(Path skillDir) {
        if (!Files.exists(skillDir.resolve("SKILL.md"))) {
            log.debug("Skipping directory (no SKILL.md): {}", skillDir);
            return null;
        }
        String pluginId = skillDir.getFileName().toString();
        try {
            SkillConfig config = FileSystemSkillConfigLoader.fromPath(skillDir);
            CapabilityDescriptor cap = CapabilityDescriptor.builder()
                    .capabilityId(pluginId + "." + config.getName())
                    .pluginId(pluginId).type(CapabilityType.SKILL)
                    .skillConfig(config)
                    .build();
            log.info("Loaded flat skill '{}' from {}", config.getName(), skillDir);
            return PluginDescriptor.builder()
                    .pluginId(pluginId).version("1.0")
                    .name(config.getName()).description(config.getDescription())
                    .tags(List.of())
                    .capabilities(List.of(cap))
                    .build();
        } catch (Exception e) {
            log.warn("Failed to load flat skill from {}: {}", skillDir, e.getMessage());
            return null;
        }
    }
}
