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

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

public class EnabledStateWriterTest {

    private final EnabledStateWriter writer = new EnabledStateWriter();
    private final EnabledStateLoader loader = new EnabledStateLoader();

    @Test
    public void setEnabled_createsFileWhenMissing() throws IOException {
        Path dir = Files.createTempDirectory("regnexe-enabled-writer-test-");
        try {
            Path file = dir.resolve("nested").resolve("enabled.yml");
            writer.setEnabled(file, "claude-md-management@claude-import", true);

            Map<String, Boolean> result = loader.load(file);
            Assert.assertEquals(Boolean.TRUE, result.get("claude-md-management@claude-import"));
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void setEnabled_preservesExistingKeys() throws IOException {
        Path dir = Files.createTempDirectory("regnexe-enabled-writer-test-");
        try {
            Path file = dir.resolve("enabled.yml");
            Files.writeString(file, "weather-tools@personal: true\n");

            writer.setEnabled(file, "security-review@default", false);

            Map<String, Boolean> result = loader.load(file);
            Assert.assertEquals(Boolean.TRUE, result.get("weather-tools@personal"));
            Assert.assertEquals(Boolean.FALSE, result.get("security-review@default"));
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void setEnabled_overwritesExistingValue() throws IOException {
        Path dir = Files.createTempDirectory("regnexe-enabled-writer-test-");
        try {
            Path file = dir.resolve("enabled.yml");
            Files.writeString(file, "weather-tools@personal: true\n");

            writer.setEnabled(file, "weather-tools@personal", false);

            Assert.assertEquals(Boolean.FALSE, loader.load(file).get("weather-tools@personal"));
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void remove_dropsKeyAndKeepsOthers() throws IOException {
        Path dir = Files.createTempDirectory("regnexe-enabled-writer-test-");
        try {
            Path file = dir.resolve("enabled.yml");
            Files.writeString(file, """
                    weather-tools@personal: true
                    security-review@default: false
                    """);

            writer.remove(file, "security-review@default");

            Map<String, Boolean> result = loader.load(file);
            Assert.assertEquals(Boolean.TRUE, result.get("weather-tools@personal"));
            Assert.assertFalse(result.containsKey("security-review@default"));
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void remove_missingFile_isNoOp() {
        Path file = Path.of("/nonexistent/enabled.yml");
        writer.remove(file, "weather-tools@personal"); // must not throw
        Assert.assertFalse(Files.exists(file));
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
