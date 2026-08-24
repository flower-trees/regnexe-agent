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

package org.salt.regnexe.agent.core.marketplace.scope;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The write side of {@link EnabledStateLoader}, which is deliberately read-only. Used by
 * {@code /plugin install|uninstall|enable|disable} (regnexe-cli) to persist the soft on/off
 * switch — disable is not uninstall, it only flips a key in {@code enabled.yml}.
 *
 * <p>Writes are read-modify-write against the whole file (not append-only), so concurrent writers
 * to the same file can clobber each other — acceptable for a single-process interactive CLI,
 * which doesn't need to defend against that class of race yet.
 */
@Slf4j
public class EnabledStateWriter {

    private final EnabledStateLoader loader = new EnabledStateLoader();

    /** Sets {@code globalId: value}, preserving every other key already in the file. */
    public void setEnabled(Path enabledYml, String globalId, boolean value) {
        Map<String, Boolean> current = new LinkedHashMap<>(loader.load(enabledYml));
        current.put(globalId, value);
        write(enabledYml, current);
    }

    /** Removes {@code globalId} entirely (used by uninstall — no {@code false} residue left behind). */
    public void remove(Path enabledYml, String globalId) {
        Map<String, Boolean> current = new LinkedHashMap<>(loader.load(enabledYml));
        if (current.remove(globalId) == null) {
            return; // wasn't declared — nothing to persist
        }
        write(enabledYml, current);
    }

    private void write(Path enabledYml, Map<String, Boolean> content) {
        try {
            if (enabledYml.getParent() != null) {
                Files.createDirectories(enabledYml.getParent());
            }
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            String yaml = new Yaml(options).dump(content);
            // write-to-temp-then-rename: a REPL crash mid-write leaves the old file intact
            // instead of a truncated/corrupt enabled.yml.
            Path tmp = enabledYml.resolveSibling(enabledYml.getFileName() + ".tmp");
            Files.writeString(tmp, yaml, StandardCharsets.UTF_8);
            Files.move(tmp, enabledYml, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Failed to write enabled.yml '{}': {}", enabledYml, e.getMessage());
            throw new UncheckedIOException(e);
        }
    }
}
