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
import org.junit.Test;
import org.salt.regnexe.agent.core.marketplace.SimpleMarketplace;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginDescriptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;

/**
 * End-to-end: {@link PluginCacheInstaller#install} followed by
 * {@link DefaultPluginManager#addPluginDirectory} against the resolved {@code cache/<id>/<hash>/}
 * path — exercises exactly what {@code regnexe-cli}'s {@code buildAgent()} does after
 * {@code /plugin install}.
 *
 * <p>Regression coverage for a bug found via harness-testbed's case 002 re-run: a plugin whose
 * manifest declares only {@code name} (no {@code pluginId} — true of most real Claude Code
 * {@code plugin.json} files, including the real {@code claude-md-management} plugin used in that
 * case) used to get silently registered under the cache hash as its pluginId instead of its real
 * id, because {@link ManifestPluginLoader#load(Path, PluginManifest)}'s directory-name fallback
 * doesn't know the leaf directory it's given is a hash, not an id. The practical symptom:
 * {@code /plugin disable <id>@<marketplace>} wrote the right key to {@code enabled.yml} but
 * silently no-op'd at runtime, because it was toggling a pluginId that didn't match what was
 * actually registered.
 */
public class CachedPluginLoadingTest {

    private final PluginCacheInstaller installer = new PluginCacheInstaller();

    @Test
    public void installedPlugin_registersUnderRealPluginId_notTheCacheHash() throws IOException {
        Path root = Files.createTempDirectory("regnexe-cached-plugin-loading-test-");
        try {
            Path source = writeSourceWithNameOnlyManifest(root, "claude-md-management");
            Path marketplaceRoot = root.resolve("marketplace");

            PluginCacheInstaller.InstallResult result = installer.install(source, marketplaceRoot);
            // sanity: install itself got the id right (from the source dir name)
            Assert.assertEquals("claude-md-management", result.pluginId());

            Optional<Path> resolved = installer.resolveCurrent(marketplaceRoot, "claude-md-management");
            Assert.assertTrue(resolved.isPresent());
            // the resolved load path is hash-named, not id-named — the crux of the bug
            Assert.assertEquals(result.hash(), resolved.get().getFileName().toString());

            SimpleMarketplace marketplace = new SimpleMarketplace();
            DefaultPluginManager mgr = new DefaultPluginManager();
            mgr.addPluginDirectory(resolved.get().toString());
            marketplace.load(mgr);

            PluginDescriptor plugin = marketplace.listEnabled().stream()
                    .filter(p -> p.getPluginId().equals("claude-md-management"))
                    .findFirst().orElse(null);
            Assert.assertNotNull(
                    "plugin must be registered under its real id ('claude-md-management'), "
                            + "not the cache hash directory name — actual ids: "
                            + marketplace.listEnabled().stream().map(PluginDescriptor::getPluginId).toList(),
                    plugin);

            // enable/disable must actually work against the real id — this is what silently
            // no-op'd in harness-testbed before the fix
            marketplace.disable("claude-md-management");
            Assert.assertTrue("disable() must remove it from listEnabled()",
                    marketplace.listEnabled().isEmpty());
        } finally {
            deleteTree(root);
        }
    }

    private Path writeSourceWithNameOnlyManifest(Path root, String pluginId) throws IOException {
        Path source = root.resolve("sources").resolve(pluginId);
        Path claudePluginDir = source.resolve(".claude-plugin");
        Files.createDirectories(claudePluginDir);
        // Real Claude Code plugin.json files commonly declare "name" but not "pluginId" — see
        // the real claude-md-management plugin.json used in harness-testbed case 002.
        Files.writeString(claudePluginDir.resolve("plugin.json"), """
                {
                  "name": "%s",
                  "description": "test plugin with a name-only manifest",
                  "version": "1.0.0"
                }
                """.formatted(pluginId));
        Path skillDir = source.resolve("skills").resolve("do-something");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: do-something
                description: "test skill"
                ---
                test skill body
                """);
        return source;
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
