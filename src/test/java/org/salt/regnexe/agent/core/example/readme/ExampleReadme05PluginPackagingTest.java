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
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.regnexe.agent.core.RegnexeAgent;
import org.salt.regnexe.agent.core.RegnexeAgentBuilder;
import org.salt.regnexe.agent.core.TestApplication;
import org.salt.regnexe.agent.core.common.enums.CapabilityType;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.ConsoleEventListener;
import org.salt.regnexe.agent.core.llm.Vendor;
import org.salt.regnexe.agent.core.market.DefaultPluginManager;
import org.salt.regnexe.agent.core.market.SimpleMarketplace;
import org.salt.regnexe.agent.core.market.plugin.CapabilityDescriptor;
import org.salt.regnexe.agent.core.market.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * README — Plugin packaging.
 *
 * Test 1 (LLM): code-first packaging — {@code PluginDescriptor.builder().tool(...).skillConfig(...)
 * .subAgentConfig(...)} bundles one of each capability type into a single plugin in one call,
 * instead of hand-building each {@link CapabilityDescriptor} separately. {@code withPlugin(PluginDescriptor...)}
 * installs it directly — no manual {@code SimpleMarketplace} construction needed.
 *
 * Test 2 (no LLM): package scan — {@code DefaultPluginManager.scanPackages(...)} discovers
 * {@code @Plugin} classes on the classpath.
 *
 * Test 3 (no LLM): file-system directory — {@code DefaultPluginManager.addDirectory(...)} loads
 * a {@code plugin.yaml} + {@code tools/} folder from disk.
 *
 * Prerequisites: Test 1 needs env var DASHSCOPE_API_KEY; Tests 2-3 do not call an LLM.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class ExampleReadme05PluginPackagingTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void pluginDescriptorShouldBundleToolSkillAndSubAgent() {
        Tool weatherTool = Tool.builder()
                .name("get_weather")
                .description("Get today's weather for a city.")
                .params("city: String -- city name")
                .func(city -> "Beijing: sunny, 22 C, excellent air quality.")
                .build();

        // allowedTools must reference the fully-qualified capabilityId ("pluginId.name"),
        // since the tool and the skill share the same pluginId in this bundle.
        SkillConfig travelAdvisor = SkillConfig.builder()
                .name("travel_advisor")
                .description("Call get_weather for the city, Gives outdoor-activity advice based on weather. " +
                             "TRIGGER: Use when the user asks whether the weather suits an outdoor activity.")
                .systemPrompt("Call get_weather for the city, then give a short go/no-go running recommendation.")
                .allowedTools(List.of("get_weather"))
                .build();

        Tool estimateCostTool = Tool.builder()
                .name("estimate_trip_cost")
                .description("Estimates total cost for a multi-day business trip.")
                .params("days: int; city: String")
                .func(input -> "3-day Chengdu trip estimate: 3600 CNY total.")
                .build();

        SubAgentConfig expenseEstimator = SubAgentConfig.builder()
                .name("expense_estimator")
                .description("Estimates business trip cost. TRIGGER: Use when the user asks for a trip budget.")
                .model("inherit")
                .systemPrompt("Call estimate_trip_cost, then report the total.")
                .ownTools(List.of(estimateCostTool))   // private — never exposed to the outer agent
                .build();

        PluginDescriptor tripPlugin = PluginDescriptor.builder()
                .pluginId("trip-plugin")
                .version("1.0")
                .name("Trip Plugin")
                .description("Bundles a tool, a skill, and a sub-agent for trip planning")
                .tool(weatherTool)
                .skillConfig(travelAdvisor)
                .subAgentConfig(expenseEstimator)
                .build();

        // All three were registered correctly, each id'd as "trip-plugin.<name>".
        Assert.assertEquals(CapabilityType.MCP_TOOL, findCapability(tripPlugin, "trip-plugin.get_weather").getType());
        Assert.assertEquals(CapabilityType.SKILL, findCapability(tripPlugin, "trip-plugin.travel_advisor").getType());
        Assert.assertEquals(CapabilityType.SUB_AGENT, findCapability(tripPlugin, "trip-plugin.expense_estimator").getType());

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPlugin(tripPlugin)        // installs the PluginDescriptor directly — no marketplace to construct
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute(
                "I want to go for a run in Beijing today. Should I, and what should I watch out for?");

        System.out.println("\n========== ExampleReadme05 PluginDescriptor Result ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("==============================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    private CapabilityDescriptor findCapability(PluginDescriptor plugin, String capabilityId) {
        return plugin.getCapabilities().stream()
                .filter(cap -> capabilityId.equals(cap.getCapabilityId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Capability not found: " + capabilityId));
    }

    @Test
    public void packageScanShouldDiscoverPlugin() {
        DefaultPluginManager mgr = new DefaultPluginManager();
        mgr.scanPackages("org.salt.regnexe.agent.core.example.testplugins");
        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.load(mgr);

        CapabilityDescriptor cap = marketplace.resolveDescriptor("test-weather-plugin.get_weather");
        Assert.assertNotNull("scanPackages() should discover the @Plugin class on the classpath", cap);
        Assert.assertEquals(CapabilityType.MCP_TOOL, cap.getType());
    }

    @Test
    public void fileSystemDirectoryShouldDiscoverPlugin() throws IOException {
        Path baseDir = Files.createTempDirectory("regnexe-readme-plugin-");
        Path pluginDir = baseDir.resolve("dir-weather-plugin");
        Path toolsDir = pluginDir.resolve("tools");
        try {
            Files.createDirectories(toolsDir);
            Files.writeString(pluginDir.resolve("plugin.yaml"),
                    "pluginId: dir-weather-plugin\nname: Dir Weather Plugin\nversion: 1.0\n" +
                    "description: Directory-loaded weather plugin\ntags: [weather]\n");
            Files.writeString(toolsDir.resolve("get_weather.sh"),
                    "#!/bin/bash\necho 'Beijing: sunny, 22 C.'\n");
            Files.writeString(toolsDir.resolve("get_weather.yaml"),
                    "description: \"Get today's weather for a city\"\nparams: \"city: String\"\ntags: [weather]\n");

            SimpleMarketplace marketplace = new SimpleMarketplace();
            marketplace.load(new DefaultPluginManager().addDirectory(baseDir.toString()));

            CapabilityDescriptor cap = marketplace.resolveDescriptor("dir-weather-plugin.get_weather");
            Assert.assertNotNull("addDirectory() should discover the plugin.yaml + tools/ folder", cap);
            Assert.assertEquals(CapabilityType.MCP_TOOL, cap.getType());
        } finally {
            try (var walk = Files.walk(baseDir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException ignored) {
                    }
                });
            }
        }
    }
}
