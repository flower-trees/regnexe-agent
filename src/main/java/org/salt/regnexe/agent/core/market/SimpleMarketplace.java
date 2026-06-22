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

package org.salt.regnexe.agent.core.market;

import lombok.extern.slf4j.Slf4j;
import org.salt.regnexe.agent.core.market.plugin.CapabilityDescriptor;
import org.salt.regnexe.agent.core.market.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.task.state.capability.CapabilityCandidate;
import org.salt.regnexe.agent.core.task.state.capability.CapabilitySearchResult;
import org.salt.regnexe.agent.core.task.state.capability.SearchQuery;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory Marketplace for M0.
 * Install plugins manually; search returns all enabled capabilities.
 * Each capability carries its own Tool — no separate registration needed.
 */
@Slf4j
public class SimpleMarketplace implements Marketplace {

    private final Map<String, PluginDescriptor> plugins = new LinkedHashMap<>();
    private final Set<String> enabled = new HashSet<>();
    private final Set<String> capabilityIds = new HashSet<>();

    /**
     * Rejects duplicate pluginId/capabilityId rather than silently overwriting.
     * capabilityId in particular is echoed verbatim by the planner LLM for exact-match
     * capability selection (see TaskPlanner) — a silent collision there would make two
     * unrelated capabilities indistinguishable to the LLM.
     */
    @Override
    public void install(PluginDescriptor plugin) {
        String pluginId = plugin.getPluginId();
        if (plugins.containsKey(pluginId)) {
            throw new IllegalStateException("Plugin already installed: " + pluginId);
        }
        List<CapabilityDescriptor> caps = plugin.getCapabilities() != null
                ? plugin.getCapabilities() : List.of();
        for (CapabilityDescriptor cap : caps) {
            if (capabilityIds.contains(cap.getCapabilityId())) {
                throw new IllegalStateException("Capability id already registered: " + cap.getCapabilityId());
            }
        }
        caps.forEach(cap -> capabilityIds.add(cap.getCapabilityId()));
        plugins.put(pluginId, plugin);
        enabled.add(pluginId);
        log.debug("Installed plugin: {}", pluginId);
    }

    @Override
    public void uninstall(String pluginId) {
        PluginDescriptor removed = plugins.remove(pluginId);
        enabled.remove(pluginId);
        if (removed != null && removed.getCapabilities() != null) {
            removed.getCapabilities().forEach(cap -> capabilityIds.remove(cap.getCapabilityId()));
        }
    }

    @Override
    public void enable(String pluginId) {
        enabled.add(pluginId);
    }

    @Override
    public void disable(String pluginId) {
        enabled.remove(pluginId);
    }

    /**
     * M0: returns all enabled capabilities, ignoring search terms.
     */
    @Override
    public CapabilitySearchResult search(SearchQuery query) {
        Set<String> excludeIds = query.getExcludeIds() != null
                ? new HashSet<>(query.getExcludeIds())
                : Set.of();

        List<CapabilityCandidate> candidates = new ArrayList<>();
        for (PluginDescriptor plugin : plugins.values()) {
            if (!enabled.contains(plugin.getPluginId())) continue;
            for (CapabilityDescriptor cap : plugin.getCapabilities()) {
                if (excludeIds.contains(cap.getCapabilityId())) continue;
                CapabilityCandidate c = new CapabilityCandidate();
                c.setCapabilityId(cap.getCapabilityId());
                c.setPluginId(cap.getPluginId());
                c.setType(cap.getType());
                c.setName(cap.getName());
                c.setDescription(cap.getDescription());
                c.setAllowedTools(allowedTools(cap));
                c.setScore(1.0);
                candidates.add(c);
            }
        }

        CapabilitySearchResult result = new CapabilitySearchResult();
        result.setCandidates(candidates);
        result.setSearchStrategy("full_scan");
        result.setSearchedAt(System.currentTimeMillis());
        return result;
    }

    private List<String> allowedTools(CapabilityDescriptor cap) {
        if (cap.getSkillConfig() != null) {
            return cap.getSkillConfig().getAllowedTools() != null
                    ? cap.getSkillConfig().getAllowedTools()
                    : List.of();
        }
        if (cap.getSubAgentConfig() != null) {
            return cap.getSubAgentConfig().getAllowedTools() != null
                    ? cap.getSubAgentConfig().getAllowedTools()
                    : List.of();
        }
        return List.of();
    }

    @Override
    public CapabilityDescriptor resolveDescriptor(String capabilityId) {
        for (PluginDescriptor plugin : plugins.values()) {
            if (!enabled.contains(plugin.getPluginId())) continue;
            for (CapabilityDescriptor cap : plugin.getCapabilities()) {
                if (capabilityId.equals(cap.getCapabilityId())) {
                    return cap;
                }
            }
        }
        return null;
    }

    @Override
    public List<PluginDescriptor> listEnabled() {
        return plugins.values().stream()
                .filter(p -> enabled.contains(p.getPluginId()))
                .toList();
    }
}
