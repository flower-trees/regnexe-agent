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
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses one {@code enabled.yml} file into a {@code globalId -> enabled} map.
 *
 * <pre>
 * # ~/.rex/enabled.yml
 * weather-tools@personal: false
 * security-review@default: true
 * </pre>
 *
 * <p>A missing file is not an error — it just means this scope declared nothing, so
 * {@link #load} returns an empty map (see {@link ScopeResolver} for how that's merged).
 */
@Slf4j
public class EnabledStateLoader {

    @SuppressWarnings("unchecked")
    public Map<String, Boolean> load(Path enabledYml) {
        if (!Files.exists(enabledYml)) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = new Yaml().load(Files.readString(enabledYml));
            if (raw == null) return Map.of();
            Map<String, Boolean> result = new LinkedHashMap<>();
            raw.forEach((key, value) -> result.put(key, Boolean.parseBoolean(String.valueOf(value))));
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse enabled.yml '{}': {}", enabledYml, e.getMessage());
            return Map.of();
        }
    }
}
