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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class PluginCacheInstallerTest {

    private final PluginCacheInstaller installer = new PluginCacheInstaller();

    @Test
    public void install_copiesContentAndPointsCurrentAtItsHash() throws IOException {
        Path root = Files.createTempDirectory("regnexe-cache-installer-test-");
        try {
            Path source = writeSource(root, "hello-plugin", "value: 1");
            Path marketplaceRoot = root.resolve("marketplace");

            PluginCacheInstaller.InstallResult result = installer.install(source, marketplaceRoot);

            Assert.assertEquals("hello-plugin", result.pluginId());
            Assert.assertFalse(result.alreadyPresent());
            Assert.assertTrue(Files.isDirectory(result.installedPath()));
            Assert.assertTrue(Files.exists(result.installedPath().resolve("plugin.yaml")));

            Optional<Path> current = installer.resolveCurrent(marketplaceRoot, "hello-plugin");
            Assert.assertTrue(current.isPresent());
            Assert.assertEquals(result.installedPath(), current.get());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void install_sameContentTwice_isIdempotentNoOp() throws IOException {
        Path root = Files.createTempDirectory("regnexe-cache-installer-test-");
        try {
            Path source = writeSource(root, "hello-plugin", "value: 1");
            Path marketplaceRoot = root.resolve("marketplace");

            PluginCacheInstaller.InstallResult first = installer.install(source, marketplaceRoot);
            PluginCacheInstaller.InstallResult second = installer.install(source, marketplaceRoot);

            Assert.assertFalse(first.alreadyPresent());
            Assert.assertTrue(second.alreadyPresent());
            Assert.assertEquals(first.hash(), second.hash());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void install_differentContent_producesDifferentHashAndMovesCurrent() throws IOException {
        Path root = Files.createTempDirectory("regnexe-cache-installer-test-");
        try {
            Path source = writeSource(root, "hello-plugin", "value: 1");
            Path marketplaceRoot = root.resolve("marketplace");

            PluginCacheInstaller.InstallResult v1 = installer.install(source, marketplaceRoot);
            Files.writeString(source.resolve("data.txt"), "value: 2");
            PluginCacheInstaller.InstallResult v2 = installer.install(source, marketplaceRoot);

            Assert.assertNotEquals(v1.hash(), v2.hash());
            // old version stays on disk (uninstall is the only thing that removes cache content)
            Assert.assertTrue(Files.isDirectory(v1.installedPath()));
            Assert.assertEquals(v2.installedPath(), installer.resolveCurrent(marketplaceRoot, "hello-plugin").get());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void install_excludesDotGitFromHashAndCopy() throws IOException {
        Path root = Files.createTempDirectory("regnexe-cache-installer-test-");
        try {
            Path source = writeSource(root, "hello-plugin", "value: 1");
            Files.createDirectories(source.resolve(".git"));
            Files.writeString(source.resolve(".git").resolve("HEAD"), "ref: refs/heads/main");
            Path marketplaceRoot = root.resolve("marketplace");

            PluginCacheInstaller.InstallResult withoutGitChange = installer.install(source, marketplaceRoot);
            Assert.assertFalse(Files.exists(withoutGitChange.installedPath().resolve(".git")));

            // mutating .git content must not change the hash — it's excluded from hashing
            Files.writeString(source.resolve(".git").resolve("HEAD"), "ref: refs/heads/other");
            PluginCacheInstaller.InstallResult afterGitChange = installer.install(source, marketplaceRoot);
            Assert.assertEquals(withoutGitChange.hash(), afterGitChange.hash());
            Assert.assertTrue(afterGitChange.alreadyPresent());
        } finally {
            deleteTree(root);
        }
    }

    /**
     * Regression: {@code Files.walk()} only follows symlinks it meets *during* recursion, not
     * the root path itself, so hashing/copying straight off a symlinked source (the exact
     * harness-testbed case 002 setup: {@code plugins/<id>} symlinked to a real external plugin)
     * used to silently produce the SHA-256-of-nothing hash ({@code e3b0c44298fc}) and an empty
     * cache directory instead of the real content.
     */
    @Test
    public void install_sourceIsSymlinkToDirectory_resolvesRealContent() throws IOException {
        Path root = Files.createTempDirectory("regnexe-cache-installer-test-");
        try {
            Path realSource = writeSource(root, "hello-plugin", "value: 1");
            Path symlinkSource = root.resolve("via-symlink");
            Files.createSymbolicLink(symlinkSource, realSource);
            Path marketplaceRoot = root.resolve("marketplace");

            PluginCacheInstaller.InstallResult result = installer.install(symlinkSource, marketplaceRoot);

            Assert.assertNotEquals("e3b0c44298fc", result.hash()); // not the empty-input hash
            Assert.assertTrue(Files.exists(result.installedPath().resolve("plugin.yaml")));
            Assert.assertTrue(Files.exists(result.installedPath().resolve("data.txt")));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void install_missingManifest_throws() throws IOException {
        Path root = Files.createTempDirectory("regnexe-cache-installer-test-");
        try {
            Path source = root.resolve("no-manifest");
            Files.createDirectories(source);
            Files.writeString(source.resolve("data.txt"), "no manifest here");

            Assert.assertThrows(IllegalArgumentException.class,
                    () -> installer.install(source, root.resolve("marketplace")));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void uninstall_removesEntireCacheDirIncludingHistory() throws IOException {
        Path root = Files.createTempDirectory("regnexe-cache-installer-test-");
        try {
            Path source = writeSource(root, "hello-plugin", "value: 1");
            Path marketplaceRoot = root.resolve("marketplace");
            installer.install(source, marketplaceRoot);
            Files.writeString(source.resolve("data.txt"), "value: 2");
            installer.install(source, marketplaceRoot); // second historical version

            boolean removed = installer.uninstall(marketplaceRoot, "hello-plugin");

            Assert.assertTrue(removed);
            Assert.assertFalse(Files.exists(marketplaceRoot.resolve("cache").resolve("hello-plugin")));
            Assert.assertTrue(installer.resolveCurrent(marketplaceRoot, "hello-plugin").isEmpty());
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void uninstall_nothingInstalled_returnsFalseNotError() throws IOException {
        Path root = Files.createTempDirectory("regnexe-cache-installer-test-");
        try {
            Assert.assertFalse(installer.uninstall(root.resolve("marketplace"), "never-installed"));
        } finally {
            deleteTree(root);
        }
    }

    @Test
    public void listInstalledIds_reflectsInstallAndUninstall() throws IOException {
        Path root = Files.createTempDirectory("regnexe-cache-installer-test-");
        try {
            Path marketplaceRoot = root.resolve("marketplace");
            installer.install(writeSource(root, "plugin-a", "value: 1"), marketplaceRoot);
            installer.install(writeSource(root, "plugin-b", "value: 1"), marketplaceRoot);

            List<String> ids = installer.listInstalledIds(marketplaceRoot);
            Assert.assertEquals(List.of("plugin-a", "plugin-b"), ids);

            installer.uninstall(marketplaceRoot, "plugin-a");
            Assert.assertEquals(List.of("plugin-b"), installer.listInstalledIds(marketplaceRoot));
        } finally {
            deleteTree(root);
        }
    }

    private Path writeSource(Path root, String pluginId, String extraContent) throws IOException {
        Path source = root.resolve("sources").resolve(pluginId);
        Files.createDirectories(source);
        Files.writeString(source.resolve("plugin.yaml"), """
                pluginId: %s
                name: %s
                description: test plugin
                """.formatted(pluginId, pluginId));
        Files.writeString(source.resolve("data.txt"), extraContent);
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
