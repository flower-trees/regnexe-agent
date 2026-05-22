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

package org.salt.regnexeagent.core.market;

/**
 * Discovers plugins from local sources (annotation scan, directory) and installs
 * them into a {@link Marketplace}.
 *
 * <p>Typical usage:
 * <pre>
 * DefaultPluginManager manager = new DefaultPluginManager()
 *     .addDirectory("/opt/regnexe-plugins")
 *     .scanPackages("com.example.plugins")
 *     .register(weatherBean);
 *
 * marketplace.load(manager);   // triggers installTo(marketplace)
 * </pre>
 */
public interface PluginManager {

    /** Accumulate a base directory path for directory-based plugin loading. */
    void loadFromDirectory(String directoryPath);

    /** Accumulate base packages for {@code @Plugin}-annotated class scanning. */
    void scanPackages(String... basePackages);

    /** Execute discovery and install all found plugins into {@code marketplace}. */
    void installTo(Marketplace marketplace);
}
