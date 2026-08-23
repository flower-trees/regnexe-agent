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

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.salt.regnexe.agent.core.marketplace.SimpleMarketplace;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityType;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Covers the manifest-compat work from docs/design/skill-slash-invocation.md (regnexe-cli):
 * {@code DefaultPluginManager} accepting Claude Code's {@code .claude-plugin/plugin.json} manifest
 * in addition to its native {@code plugin.yaml}, and degrading duplicate-pluginId install conflicts
 * to a skip instead of crashing the whole directory scan.
 */
public class DefaultPluginManagerManifestCompatTest {

    // ── plugin.json manifest recognition ──────────────────────────────────────

    @Test
    public void claudePluginJsonManifestShouldBeRecognized() throws IOException {
        Path baseDir = Files.createTempDirectory("regnexe-manifest-test-");
        try {
            Path pluginDir = baseDir.resolve("json-plugin");
            Path claudePluginDir = pluginDir.resolve(".claude-plugin");
            Files.createDirectories(claudePluginDir);
            Files.writeString(claudePluginDir.resolve("plugin.json"), """
                    {
                      "name": "json-plugin",
                      "description": "Loaded from a Claude Code style manifest",
                      "version": "1.0.0",
                      "author": {"name": "Someone", "email": "someone@example.com"}
                    }
                    """);
            writeSkill(pluginDir, "greet", "Greets the user.");

            SimpleMarketplace marketplace = new SimpleMarketplace();
            marketplace.load(new DefaultPluginManager().addDirectory(baseDir.toString()));

            CapabilityDescriptor cap = marketplace.resolveDescriptor("json-plugin.greet");
            Assert.assertNotNull("skill loaded via .claude-plugin/plugin.json manifest must resolve", cap);
            Assert.assertEquals(CapabilityType.SKILL, cap.getType());

            PluginDescriptor plugin = marketplace.listEnabled().stream()
                    .filter(p -> p.getPluginId().equals("json-plugin"))
                    .findFirst().orElseThrow();
            Assert.assertEquals("Loaded from a Claude Code style manifest", plugin.getDescription());
            Assert.assertEquals("1.0.0", plugin.getVersion());
        } finally {
            deleteTree(baseDir);
        }
    }

    @Test
    public void claudePluginJsonKeywordsShouldFallBackToTags() throws IOException {
        Path baseDir = Files.createTempDirectory("regnexe-manifest-test-");
        try {
            Path pluginDir = baseDir.resolve("json-plugin");
            Path claudePluginDir = pluginDir.resolve(".claude-plugin");
            Files.createDirectories(claudePluginDir);
            Files.writeString(claudePluginDir.resolve("plugin.json"), """
                    {"name": "json-plugin", "description": "d", "keywords": ["alpha", "beta"]}
                    """);
            writeSkill(pluginDir, "greet", "Greets the user.");

            SimpleMarketplace marketplace = new SimpleMarketplace();
            marketplace.load(new DefaultPluginManager().addDirectory(baseDir.toString()));

            PluginDescriptor plugin = marketplace.listEnabled().stream()
                    .filter(p -> p.getPluginId().equals("json-plugin"))
                    .findFirst().orElseThrow();
            Assert.assertEquals(List.of("alpha", "beta"), plugin.getTags());
        } finally {
            deleteTree(baseDir);
        }
    }

    @Test
    public void nativePluginYamlShouldTakePriorityOverPluginJson() throws IOException {
        Path baseDir = Files.createTempDirectory("regnexe-manifest-test-");
        try {
            Path pluginDir = baseDir.resolve("both-manifests");
            Files.createDirectories(pluginDir);
            Files.writeString(pluginDir.resolve("plugin.yaml"),
                    "name: From YAML\ndescription: native manifest wins\n");
            Path claudePluginDir = pluginDir.resolve(".claude-plugin");
            Files.createDirectories(claudePluginDir);
            Files.writeString(claudePluginDir.resolve("plugin.json"),
                    "{\"name\": \"From JSON\", \"description\": \"should be ignored\"}");
            writeSkill(pluginDir, "greet", "Greets the user.");

            SimpleMarketplace marketplace = new SimpleMarketplace();
            marketplace.load(new DefaultPluginManager().addDirectory(baseDir.toString()));

            PluginDescriptor plugin = marketplace.listEnabled().stream()
                    .filter(p -> p.getPluginId().equals("both-manifests"))
                    .findFirst().orElseThrow();
            Assert.assertEquals("From YAML", plugin.getName());
            Assert.assertEquals("native manifest wins", plugin.getDescription());
        } finally {
            deleteTree(baseDir);
        }
    }

    @Test
    public void directoryWithoutAnyManifestShouldBeSkippedWithoutError() throws IOException {
        Path baseDir = Files.createTempDirectory("regnexe-manifest-test-");
        try {
            Path pluginDir = baseDir.resolve("no-manifest");
            writeSkill(pluginDir, "greet", "Greets the user.");
            // no plugin.yaml, no .claude-plugin/plugin.json

            SimpleMarketplace marketplace = new SimpleMarketplace();
            marketplace.load(new DefaultPluginManager().addDirectory(baseDir.toString()));

            Assert.assertTrue("directory with no manifest must not register any plugin",
                    marketplace.listEnabled().isEmpty());
        } finally {
            deleteTree(baseDir);
        }
    }

    // ── flat "personal skill" layout: dedicated skills/ root, bare SKILL.md, no manifest ─
    //
    // Per docs/design/marketplace-plugin-design.md §3.1, this layout now lives under a
    // dedicated skills/ root (FlatSkillLoader / addSkillsDirectory), not as a fallback inside
    // a manifest-plugin directory scan (addDirectory) — see
    // directoryWithoutAnyManifestShouldBeSkippedWithoutError below for the addDirectory side.

    @Test
    public void skillsDirectoryWithBareSkillMd_loadsAsSingleSkillPlugin() throws IOException {
        Path skillsDir = Files.createTempDirectory("regnexe-skills-test-");
        try {
            Path skillDir = skillsDir.resolve("tang-poetry-composer");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                    ---
                    name: tang-poetry-composer
                    description: Compose classical Tang poetry.
                    ---
                    Body text.
                    """);
            // no plugin.yaml, no .claude-plugin/plugin.json, no further nesting —
            // exactly the shape skill-creator itself produces for a standalone skill.

            SimpleMarketplace marketplace = new SimpleMarketplace();
            marketplace.load(new DefaultPluginManager().addSkillsDirectory(skillsDir.toString()));

            CapabilityDescriptor cap = marketplace.resolveDescriptor("tang-poetry-composer.tang-poetry-composer");
            Assert.assertNotNull("flat SKILL.md under skills/ must load without any manifest", cap);
            Assert.assertEquals(CapabilityType.SKILL, cap.getType());
            Assert.assertEquals("tang-poetry-composer", cap.getSkillConfig().getName());
        } finally {
            deleteTree(skillsDir);
        }
    }

    @Test
    public void addDirectoryNoLongerFallsBackToFlatSkillLoading() throws IOException {
        Path baseDir = Files.createTempDirectory("regnexe-manifest-test-");
        try {
            Path pluginDir = baseDir.resolve("tang-poetry-composer");
            Files.createDirectories(pluginDir);
            Files.writeString(pluginDir.resolve("SKILL.md"), """
                    ---
                    name: tang-poetry-composer
                    description: Compose classical Tang poetry.
                    ---
                    Body text.
                    """);

            SimpleMarketplace marketplace = new SimpleMarketplace();
            marketplace.load(new DefaultPluginManager().addDirectory(baseDir.toString()));

            Assert.assertTrue("addDirectory must not treat a bare SKILL.md as a manifest-less plugin anymore",
                    marketplace.listEnabled().isEmpty());
        } finally {
            deleteTree(baseDir);
        }
    }

    // ── pluginId collision degrades to a skip, not a crash ───────────────────

    @Test
    public void duplicatePluginIdAcrossDirectoriesShouldSkipInsteadOfThrow() throws IOException {
        Path projectDir = Files.createTempDirectory("regnexe-manifest-test-project-");
        Path userDir = Files.createTempDirectory("regnexe-manifest-test-user-");
        try {
            Path projectPlugin = projectDir.resolve("demo");
            Files.writeString(mkPluginYaml(projectPlugin), "name: Project Demo\ndescription: from project dir\n");
            writeSkill(projectPlugin, "hello", "project-level hello");

            Path userPlugin = userDir.resolve("demo");
            Files.writeString(mkPluginYaml(userPlugin), "name: User Demo\ndescription: from user dir\n");
            writeSkill(userPlugin, "hello", "user-level hello");

            SimpleMarketplace marketplace = new SimpleMarketplace();
            // project dir listed first -> project-level "demo" must win, user-level must be skipped
            // (not throw) rather than crashing the whole load.
            marketplace.load(new DefaultPluginManager()
                    .addDirectory(projectDir.toString())
                    .addDirectory(userDir.toString()));

            List<PluginDescriptor> installed = marketplace.listEnabled().stream()
                    .filter(p -> p.getPluginId().equals("demo"))
                    .toList();
            Assert.assertEquals("only the first-scanned 'demo' plugin should be installed", 1, installed.size());
            Assert.assertEquals("from project dir", installed.get(0).getDescription());
        } finally {
            deleteTree(projectDir);
            deleteTree(userDir);
        }
    }

    // ── real, locally-installed Claude Code plugin directory ─────────────────

    private static final Path REAL_CLAUDE_SKILL_PLUGIN_DIR = Path.of(System.getProperty("user.home"),
            ".claude", "plugins", "marketplaces", "claude-plugins-official",
            "plugins", "claude-md-management");

    @Test
    public void realClaudeCodePluginDirectoryShouldLoadViaJsonManifest() {
        Assume.assumeTrue("skip: claude-md-management plugin not installed on this machine ("
                        + REAL_CLAUDE_SKILL_PLUGIN_DIR + ")",
                Files.isDirectory(REAL_CLAUDE_SKILL_PLUGIN_DIR));

        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.load(new DefaultPluginManager()
                .addDirectory(REAL_CLAUDE_SKILL_PLUGIN_DIR.getParent().toString()));

        CapabilityDescriptor cap = marketplace.resolveDescriptor("claude-md-management.claude-md-improver");
        Assert.assertNotNull("claude-md-improver skill must load from the real plugin directory", cap);
        Assert.assertEquals(CapabilityType.SKILL, cap.getType());

        PluginDescriptor plugin = marketplace.listEnabled().stream()
                .filter(p -> p.getPluginId().equals("claude-md-management"))
                .findFirst().orElseThrow();
        Assert.assertEquals("1.0.0", plugin.getVersion());

        System.out.println("[real plugin dir] installed: " + plugin.getPluginId()
                + " v" + plugin.getVersion() + " — " + plugin.getDescription());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Path mkPluginYaml(Path pluginDir) throws IOException {
        Files.createDirectories(pluginDir);
        return pluginDir.resolve("plugin.yaml");
    }

    private void writeSkill(Path pluginDir, String skillName, String description) throws IOException {
        Path skillDir = pluginDir.resolve("skills").resolve(skillName);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: %s
                description: "%s"
                ---
                %s
                """.formatted(skillName, description, description));
    }

    private void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
    }
}
