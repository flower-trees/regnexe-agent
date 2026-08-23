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

package org.salt.regnexe.agent.core.example.readme;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.regnexe.agent.core.RegnexeAgent;
import org.salt.regnexe.agent.core.RegnexeAgentBuilder;
import org.salt.regnexe.agent.core.TestApplication;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.ConsoleEventListener;
import org.salt.regnexe.agent.core.llm.Vendor;
import org.salt.regnexe.agent.core.marketplace.Marketplace;
import org.salt.regnexe.agent.core.marketplace.SimpleMarketplace;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.salt.regnexe.agent.core.task.state.capability.CapabilityCandidate;
import org.salt.regnexe.agent.core.task.state.capability.CapabilitySearchResult;
import org.salt.regnexe.agent.core.task.state.capability.SearchQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * README — Marketplace: the capability index behind every {@code with*} method.
 *
 * Test 1 (no LLM): the default {@link SimpleMarketplace} — install + search + resolve.
 *
 * Test 2 (LLM): a custom {@link Marketplace} implementation standing in for a DB-backed store
 * (a real one would back {@code table} with a JPA repository / JDBC template instead of a Map).
 * Implementing the interface is the only requirement — {@code withPluginMarket(yourMarketplace)}
 * plugs it into the agent with no other code changes.
 *
 * Prerequisites: Test 2 needs env var DASHSCOPE_API_KEY.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class ExampleReadme06MarketplaceTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void defaultSimpleMarketplaceShouldInstallSearchAndResolve() {
        Tool weatherTool = Tool.builder()
                .name("get_weather")
                .description("Get today's weather for a city.")
                .params("city: String -- city name")
                .func(city -> "Beijing: sunny, 22 C.")
                .build();

        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.install(PluginDescriptor.builder()
                .pluginId("weather-plugin").version("1.0")
                .name("Weather Plugin").description("Weather queries")
                .tool(weatherTool)
                .build());

        SearchQuery query = new SearchQuery();
        query.setGoal("Check today's weather in Beijing");
        query.setTopK(5);
        CapabilitySearchResult searchResult = marketplace.search(query);

        Assert.assertEquals(1, searchResult.getCandidates().size());
        Assert.assertEquals("weather-plugin.get_weather", searchResult.getCandidates().get(0).getCapabilityId());

        CapabilityDescriptor resolved = marketplace.resolveDescriptor("weather-plugin.get_weather");
        Assert.assertNotNull(resolved);
        Assert.assertSame(weatherTool, resolved.getTool());
    }

    @Test
    public void customMarketplaceShouldInstallQueryAndRunAgent() {
        Tool weatherTool = Tool.builder()
                .name("get_weather")
                .description("Get today's weather for a city.")
                .params("city: String -- city name")
                .func(city -> "Beijing: sunny, 22 C, excellent air quality.")
                .build();

        PluginDescriptor weatherPlugin = PluginDescriptor.builder()
                .pluginId("weather-plugin").version("1.0")
                .name("Weather Plugin").description("Weather queries")
                .tags(List.of("weather"))
                .tool(weatherTool)
                .build();

        InMemoryDbMarketplace marketplace = new InMemoryDbMarketplace();
        marketplace.install(weatherPlugin);

        // Custom query beyond the Marketplace interface — e.g. an ops/admin screen.
        List<PluginDescriptor> weatherPlugins = marketplace.findByTag("weather");
        Assert.assertEquals(1, weatherPlugins.size());
        Assert.assertEquals("weather-plugin", weatherPlugins.get(0).getPluginId());

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPluginMarket(marketplace)        // any Marketplace implementation plugs in here
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute("Check today's Beijing weather. Is it good for running?");

        System.out.println("\n========== ExampleReadme06 Custom Marketplace Result ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("================================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    /**
     * Minimal custom Marketplace, e.g. backed by a database table in a real deployment.
     * {@code table} here is an in-memory stand-in for that table.
     */
    private static class InMemoryDbMarketplace implements Marketplace {

        private final Map<String, PluginDescriptor> table = new LinkedHashMap<>();
        private final Set<String> enabledIds = new HashSet<>();

        @Override
        public void install(PluginDescriptor plugin) {
            table.put(plugin.getPluginId(), plugin);
            enabledIds.add(plugin.getPluginId());
        }

        @Override
        public void uninstall(String pluginId) {
            table.remove(pluginId);
            enabledIds.remove(pluginId);
        }

        @Override
        public void enable(String pluginId) {
            enabledIds.add(pluginId);
        }

        @Override
        public void disable(String pluginId) {
            enabledIds.remove(pluginId);
        }

        @Override
        public CapabilitySearchResult search(SearchQuery query) {
            List<CapabilityCandidate> candidates = new ArrayList<>();
            for (PluginDescriptor plugin : table.values()) {
                if (!enabledIds.contains(plugin.getPluginId())) continue;
                for (CapabilityDescriptor cap : plugin.getCapabilities()) {
                    CapabilityCandidate c = new CapabilityCandidate();
                    c.setCapabilityId(cap.getCapabilityId());
                    c.setPluginId(cap.getPluginId());
                    c.setType(cap.getType());
                    c.setName(cap.getName());
                    c.setDescription(cap.getDescription());
                    c.setScore(1.0);
                    candidates.add(c);
                }
            }
            CapabilitySearchResult result = new CapabilitySearchResult();
            result.setCandidates(candidates);
            result.setSearchStrategy("db_full_scan");
            result.setSearchedAt(System.currentTimeMillis());
            return result;
        }

        @Override
        public CapabilityDescriptor resolveDescriptor(String capabilityId) {
            for (PluginDescriptor plugin : table.values()) {
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
            return table.values().stream().filter(p -> enabledIds.contains(p.getPluginId())).toList();
        }

        /** Custom query beyond the Marketplace interface — only the DB-backed implementation needs this. */
        List<PluginDescriptor> findByTag(String tag) {
            return table.values().stream()
                    .filter(p -> p.getTags() != null && p.getTags().contains(tag))
                    .toList();
        }
    }
}
