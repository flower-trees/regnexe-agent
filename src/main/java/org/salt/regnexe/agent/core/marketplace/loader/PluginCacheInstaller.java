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
import org.salt.regnexe.agent.core.marketplace.plugin.PluginManifest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Filesystem-only "install/uninstall" for the local-path plugin cache. No CLI/session concept
 * here — {@code regnexe-cli} does argument parsing and scope→path resolution, this class does
 * the actual copy/hash/pointer bookkeeping under a given {@code marketplaces/<name>/} root:
 *
 * <pre>
 * marketplaces/&lt;name&gt;/
 * ├── plugins/                 ← untouched by this class — the "installable" listing, not loaded
 * └── cache/
 *     └── &lt;plugin-id&gt;/
 *         ├── CURRENT           ← plain text, contains the active hash
 *         └── &lt;hash&gt;/           ← SHA-256 of source content, first 12 hex chars
 * </pre>
 *
 * <p>Only local paths are supported — no remote fetch (§6.1). Install is content-addressed and
 * therefore idempotent: re-installing identical content is a no-op beyond refreshing
 * {@code CURRENT}. Uninstall is immediate and total (§6.4) — no grace period, no orphan retention.
 */
@Slf4j
public class PluginCacheInstaller {

    private static final int HASH_LENGTH = 12;

    private final ManifestPluginLoader manifestLoader = new ManifestPluginLoader();

    /** Outcome of {@link #install}. */
    public record InstallResult(String pluginId, String hash, Path installedPath, boolean alreadyPresent) {
    }

    /**
     * Copies {@code source} into {@code marketplaceRoot/cache/<plugin-id>/<hash>/} and points
     * {@code CURRENT} at it. {@code source} must contain a {@code plugin.yaml} or
     * {@code .claude-plugin/plugin.json} manifest (reused directly from {@link ManifestPluginLoader}
     * so id/name/version resolution stays identical to normal manifest loading).
     */
    public InstallResult install(Path source, Path marketplaceRoot) {
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("Install source is not a directory: " + source);
        }
        // Files.walk() only follows symlinks it encounters *during* recursion (and only with
        // FOLLOW_LINKS, which we deliberately don't set — see the isGitPath/cycle note below);
        // it does NOT resolve the *root* path if that itself is a symlink, so a symlinked source
        // (e.g. `.rex/marketplaces/<name>/plugins/<id>` pointing at a real external plugin — the
        // exact case harness-testbed's case 002 exercises) would silently hash/copy nothing.
        // toRealPath() resolves that one hop up front so the walk below sees a real directory.
        Path resolvedSource;
        try {
            resolvedSource = source.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to resolve install source: " + source, e);
        }
        PluginManifest manifest = manifestLoader.loadManifest(resolvedSource);
        if (manifest == null) {
            throw new IllegalArgumentException(
                    "No plugin.yaml / .claude-plugin/plugin.json found under: " + source);
        }
        String pluginId = (manifest.getPluginId() != null && !manifest.getPluginId().isBlank())
                ? manifest.getPluginId() : resolvedSource.getFileName().toString();

        try {
            String hash = computeContentHash(resolvedSource);
            Path pluginCacheDir = marketplaceRoot.resolve("cache").resolve(pluginId);
            Path versionDir = pluginCacheDir.resolve(hash);
            boolean alreadyPresent = Files.isDirectory(versionDir);
            if (!alreadyPresent) {
                Files.createDirectories(versionDir);
                copyDirectory(resolvedSource, versionDir);
                log.info("Installed plugin '{}' ({}) into {}", pluginId, hash, versionDir);
            } else {
                log.info("Plugin '{}' ({}) already cached — refreshing CURRENT only", pluginId, hash);
            }
            writeCurrent(pluginCacheDir, hash);
            return new InstallResult(pluginId, hash, versionDir, alreadyPresent);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to install plugin from " + source, e);
        }
    }

    /**
     * Deletes {@code marketplaceRoot/cache/<pluginId>/} entirely — {@code CURRENT} and every
     * historical hash version, no grace period (§6.4). Returns {@code false} (not an error) when
     * nothing was installed under that id. Never touches {@code plugins/}.
     */
    public boolean uninstall(Path marketplaceRoot, String pluginId) {
        Path pluginCacheDir = marketplaceRoot.resolve("cache").resolve(pluginId);
        if (!Files.isDirectory(pluginCacheDir)) {
            return false;
        }
        try {
            deleteRecursively(pluginCacheDir);
            log.info("Uninstalled plugin '{}' from {}", pluginId, marketplaceRoot);
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to uninstall plugin '" + pluginId + "'", e);
        }
    }

    /** Resolves {@code cache/<pluginId>/CURRENT} to the actual {@code <hash>/} directory it names. */
    public Optional<Path> resolveCurrent(Path marketplaceRoot, String pluginId) {
        Path currentFile = marketplaceRoot.resolve("cache").resolve(pluginId).resolve("CURRENT");
        if (!Files.isRegularFile(currentFile)) {
            return Optional.empty();
        }
        try {
            String hash = Files.readString(currentFile, StandardCharsets.UTF_8).strip();
            Path resolved = marketplaceRoot.resolve("cache").resolve(pluginId).resolve(hash);
            return Files.isDirectory(resolved) ? Optional.of(resolved) : Optional.empty();
        } catch (IOException e) {
            log.warn("Failed to read CURRENT for '{}' under {}: {}", pluginId, marketplaceRoot, e.getMessage());
            return Optional.empty();
        }
    }

    /** Plugin ids under {@code marketplaceRoot/cache/} that currently resolve to a valid version. */
    public List<String> listInstalledIds(Path marketplaceRoot) {
        Path cacheRoot = marketplaceRoot.resolve("cache");
        if (!Files.isDirectory(cacheRoot)) {
            return List.of();
        }
        try (Stream<Path> subdirs = Files.list(cacheRoot)) {
            return subdirs.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(id -> resolveCurrent(marketplaceRoot, id).isPresent())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list cache/ under {}: {}", marketplaceRoot, e.getMessage());
            return List.of();
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    private void writeCurrent(Path pluginCacheDir, String hash) throws IOException {
        Files.createDirectories(pluginCacheDir);
        Path current = pluginCacheDir.resolve("CURRENT");
        Path tmp = pluginCacheDir.resolve("CURRENT.tmp");
        Files.writeString(tmp, hash, StandardCharsets.UTF_8);
        Files.move(tmp, current, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * SHA-256 over every regular file's relative path + content, in sorted path order —
     * deterministic regardless of filesystem iteration order. {@code .git/} is excluded (VCS
     * metadata isn't part of "the plugin"). Truncated to {@value #HASH_LENGTH} hex chars, matching
     * the short-hash style observed from Codex's own plugin cache (see case 002 in harness-testbed).
     */
    private String computeContentHash(Path sourceDir) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<Path> files = listContentFiles(sourceDir);
            for (Path file : files) {
                String relPath = toPosixRelative(sourceDir, file);
                digest.update(relPath.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Files.readAllBytes(file));
            }
            String hex = HexFormat.of().formatHex(digest.digest());
            return hex.substring(0, Math.min(HASH_LENGTH, hex.length()));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-mandated algorithm — this branch is unreachable in practice.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        for (Path src : listContentPaths(source)) {
            Path relPath = source.relativize(src);
            Path dest = relPath.toString().isEmpty() ? target : target.resolve(relPath.toString());
            if (Files.isDirectory(src)) {
                Files.createDirectories(dest);
            } else {
                Files.createDirectories(dest.getParent());
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private List<Path> listContentFiles(Path sourceDir) throws IOException {
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !isGitPath(sourceDir, p))
                    .sorted()
                    .toList();
        }
    }

    private List<Path> listContentPaths(Path sourceDir) throws IOException {
        try (Stream<Path> walk = Files.walk(sourceDir)) {
            List<Path> paths = new ArrayList<>(walk.filter(p -> !isGitPath(sourceDir, p)).sorted().toList());
            return paths;
        }
    }

    private boolean isGitPath(Path root, Path candidate) {
        for (Path part : root.relativize(candidate)) {
            if (part.toString().equals(".git")) return true;
        }
        return false;
    }

    private String toPosixRelative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.delete(p);
            }
        }
    }
}
