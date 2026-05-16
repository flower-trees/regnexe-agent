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

import org.salt.heliosagent.core.market.plugin.PluginDescriptor;
import org.salt.heliosagent.core.task.state.capability.CapabilitySearchResult;
import org.salt.heliosagent.core.task.state.capability.SearchQuery;
import org.salt.jlangchain.rag.tools.Tool;

import java.util.List;

/**
 * Capability index and discovery. Central registry for all installed plugins.
 */
public interface Marketplace {

    void install(PluginDescriptor plugin);

    void uninstall(String pluginId);

    void enable(String pluginId);

    void disable(String pluginId);

    /**
     * Called by Searcher to retrieve Top-K capability candidates
     */
    CapabilitySearchResult search(SearchQuery query);

    /**
     * Called by Executor to resolve a capabilityId to its tool/skill instance
     */
    Tool resolve(String capabilityId);

    List<PluginDescriptor> listEnabled();
}
