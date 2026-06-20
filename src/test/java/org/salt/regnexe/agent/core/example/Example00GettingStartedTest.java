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

package org.salt.regnexe.agent.core.example;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.jlangchain.rag.tools.annotation.AgentTool;
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
import org.salt.regnexe.agent.core.market.plugin.Plugin;
import org.salt.regnexe.agent.core.market.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

/**
 * Example 00: getting-started examples.
 *
 * Covers the smallest entry points shown in README:
 * - annotated @Plugin bean registration
 * - programmatic CapabilityDescriptor registration
 * - one full RegnexeAgent quick-start execution
 *
 * The metadata-only tests do not call an LLM. The full agent execution test
 * requires DASHSCOPE_API_KEY, like the other example tests.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class Example00GettingStartedTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void readmePluginBeanShouldLoadIntoMarketplace() {
        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.load(new DefaultPluginManager().register(new WeatherPlugin()));

        CapabilityDescriptor cap = marketplace.resolveDescriptor("weather.get_weather");

        Assert.assertNotNull(cap);
        Assert.assertEquals(CapabilityType.MCP_TOOL, cap.getType());
        Assert.assertEquals("weather", cap.getPluginId());
        Assert.assertEquals("get_weather", cap.getName());
        Assert.assertTrue(cap.getDescription().contains("weather"));
        Assert.assertNotNull(cap.getTool());
    }

    @Test
    public void programmaticDescriptorShouldReuseToolMetadata() {
        Tool weatherTool = weatherTool();

        CapabilityDescriptor cap = CapabilityDescriptor.builder()
                .capabilityId("db-weather.get_weather")
                .pluginId("db-weather")
                .type(CapabilityType.MCP_TOOL)
                .tags(List.of("weather", "quick-start"))
                .tool(weatherTool)
                .build();

        PluginDescriptor plugin = PluginDescriptor.builder()
                .pluginId("db-weather")
                .version("1.0")
                .name("Database Weather Plugin")
                .description("Programmatic weather plugin")
                .capabilities(List.of(cap))
                .build();

        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.install(plugin);

        CapabilityDescriptor resolved = marketplace.resolveDescriptor("db-weather.get_weather");

        Assert.assertNotNull(resolved);
        Assert.assertEquals(weatherTool.getName(), resolved.getName());
        Assert.assertEquals(weatherTool.getDescription(), resolved.getDescription());
        Assert.assertEquals(weatherTool, resolved.getTool());
    }

    @Test
    public void readmeQuickStartAgentShouldFinish() {
        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPlugin(new WeatherPlugin())
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute("Check today's Beijing weather. Is it good for running?");

        System.out.println("\n========== Example00 Quick Start Result ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("==================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    private Tool weatherTool() {
        return Tool.builder()
                .name("get_weather")
                .description("Get today's weather for a city.")
                .params("city: String -- city name")
                .func(city -> {
                    String c = city != null ? city.toString() : "";
                    if (c.contains("Beijing")) {
                        return "Beijing: sunny, 22 C, excellent air quality. Great day for running.";
                    }
                    return c + ": cloudy, 18 C. Reduce strenuous outdoor activity.";
                })
                .build();
    }

    @Plugin(id = "weather", name = "Weather Plugin", description = "Weather queries", tags = {"weather"})
    public static class WeatherPlugin {

        @AgentTool("Get today's weather for a city, including temperature and activity advice.")
        public String getWeather(String city) {
            if (city != null && city.contains("Beijing")) {
                return "Beijing: sunny, 22 C, excellent air quality. Great day for running.";
            }
            return city + ": cloudy, 18 C. Reduce strenuous outdoor activity.";
        }
    }
}
