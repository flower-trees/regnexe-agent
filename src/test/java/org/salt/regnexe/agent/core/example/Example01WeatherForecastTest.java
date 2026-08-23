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
import org.salt.regnexe.agent.core.RegnexeAgent;
import org.salt.regnexe.agent.core.RegnexeAgentBuilder;
import org.salt.regnexe.agent.core.TestApplication;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityType;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.ConsoleEventListener;
import org.salt.regnexe.agent.core.llm.Vendor;
import org.salt.regnexe.agent.core.marketplace.SimpleMarketplace;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

/**
 * Example 01: single-round weather query.
 *
 * Scenario
 * --------
 * Goal  : "Check today's weather in Beijing and tell me whether it is suitable for outdoor running."
 * Tool  : get_weather — returns deterministic fake data so the test is repeatable
 * Expect: agent finishes in at most 3 rounds with FINISHED status
 *
 * Prerequisites
 * -------------
 * Set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class Example01WeatherForecastTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void weatherQueryShouldFinish() {

        // ── Marketplace: one plugin with one weather capability ──────────────

        CapabilityDescriptor weatherCap = CapabilityDescriptor.builder()
                .capabilityId("get_weather")
                .pluginId("weather-plugin")
                .type(CapabilityType.MCP_TOOL)
                .tags(List.of("weather", "outdoor"))
                .tool(Tool.builder()
                        .name("get_weather")
                        .description("Gets today's weather for a given city, including temperature, conditions, air quality, and exercise suitability.")
                        .params("city: String -- city name, for example: Beijing or Shanghai")
                        .func(city -> {
                            String cityStr = city != null ? city.toString() : "";
                            if (cityStr.contains("Beijing")) {
                                return "Beijing weather today: sunny, 22°C, 40% humidity, level 2 wind, excellent air quality (AQI 35). " +
                                       "It is very suitable for outdoor running. Recommended times are 7-9 AM or 5-7 PM.";
                            }
                            return cityStr + " weather: cloudy, 18°C, light pollution. Reduce strenuous outdoor exercise.";
                        })
                        .build())
                .build();
        Assert.assertEquals(weatherCap.getTool().getName(), weatherCap.getName());
        Assert.assertEquals(weatherCap.getTool().getDescription(), weatherCap.getDescription());

        PluginDescriptor weatherPlugin = PluginDescriptor.builder()
                .pluginId("weather-plugin")
                .version("1.0")
                .name("Weather Plugin")
                .description("Realtime weather lookup plugin")
                .capabilities(List.of(weatherCap))
                .build();

        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.install(weatherPlugin);

        // ── Agent ────────────────────────────────────────────────────────────

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPluginMarket(marketplace)
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        // ── Execute ──────────────────────────────────────────────────────────

        AgentResult result = agent.execute("Check today's weather in Beijing and tell me whether it is suitable for outdoor running.");

        System.out.println("\n========== RegnexeAgent Result ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("TaskId   : " + result.getTaskId());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("========================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }
}
