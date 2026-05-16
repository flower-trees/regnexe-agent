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

package org.salt.heliosagent.core.market;

/**
 * Loads plugins from local sources (annotation scan, directory) and
 * registers them into the Marketplace via {@link Marketplace#install}.
 * Not needed when the Marketplace is backed by a DB, ES, or vector store —
 * those cases manage descriptors directly through Marketplace.
 */
public interface PluginManager {

    /**
     * Scan the given base packages for @Plugin annotated classes and register them
     */
    void scanPackages(String... basePackages);

    /**
     * Load plugin descriptors from the given directory and register them
     */
    void loadFromDirectory(String directoryPath);
}
