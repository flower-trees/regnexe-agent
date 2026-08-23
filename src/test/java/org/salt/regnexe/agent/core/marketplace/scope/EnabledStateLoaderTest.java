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

public class EnabledStateLoaderTest {

    private final EnabledStateLoader loader = new EnabledStateLoader();

    @Test
    public void missingFile_returnsEmptyMap() {
        Map<String, Boolean> result = loader.load(Path.of("/nonexistent/enabled.yml"));
        Assert.assertTrue(result.isEmpty());
    }

    @Test
    public void parsesGlobalIdKeyedBooleans() throws IOException {
        Path dir = Files.createTempDirectory("regnexe-enabled-yml-test-");
        try {
            Path file = dir.resolve("enabled.yml");
            Files.writeString(file, """
                    weather-tools@personal: false
                    security-review@default: true
                    """);

            Map<String, Boolean> result = loader.load(file);

            Assert.assertEquals(Boolean.FALSE, result.get("weather-tools@personal"));
            Assert.assertEquals(Boolean.TRUE, result.get("security-review@default"));
        } finally {
            deleteTree(dir);
        }
    }

    @Test
    public void emptyFile_returnsEmptyMap() throws IOException {
        Path dir = Files.createTempDirectory("regnexe-enabled-yml-test-");
        try {
            Path file = dir.resolve("enabled.yml");
            Files.writeString(file, "");

            Assert.assertTrue(loader.load(file).isEmpty());
        } finally {
            deleteTree(dir);
        }
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
