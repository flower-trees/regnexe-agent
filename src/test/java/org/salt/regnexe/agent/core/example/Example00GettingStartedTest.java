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
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
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
    public void withToolShouldRegisterMcpToolCapability() {
        Tool weatherTool = weatherTool();

        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.load(new DefaultPluginManager().registerTool(weatherTool));

        CapabilityDescriptor cap = marketplace.resolveDescriptor("get_weather");

        Assert.assertNotNull(cap);
        Assert.assertEquals(CapabilityType.MCP_TOOL, cap.getType());
        Assert.assertEquals("get_weather", cap.getPluginId());
        Assert.assertEquals("get_weather", cap.getCapabilityId());
        Assert.assertSame(weatherTool, cap.getTool());
    }

    @Test
    public void withSkillShouldRegisterSkillCapability() {
        SkillConfig config = SkillConfig.builder()
                .name("travel_advisor")
                .description("Advises on travel plans using weather and booking tools.")
                .allowedTools(List.of("get_weather"))
                .systemPrompt("You are a travel advisor. Use get_weather before recommending a plan.")
                .build();

        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.load(new DefaultPluginManager().registerSkill(config));

        CapabilityDescriptor cap = marketplace.resolveDescriptor("travel_advisor");

        Assert.assertNotNull(cap);
        Assert.assertEquals(CapabilityType.SKILL, cap.getType());
        Assert.assertEquals("travel_advisor", cap.getPluginId());
        Assert.assertSame(config, cap.getSkillConfig());
    }

    @Test
    public void withSubAgentShouldRegisterSubAgentCapability() {
        SubAgentConfig config = SubAgentConfig.builder()
                .name("data_analyst")
                .description("Analyzes raw data and reports findings.")
                .model("inherit")
                .systemPrompt("You are a data analyst. Summarize the given dataset.")
                .build();

        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.load(new DefaultPluginManager().registerSubAgent(config));

        CapabilityDescriptor cap = marketplace.resolveDescriptor("data_analyst");

        Assert.assertNotNull(cap);
        Assert.assertEquals(CapabilityType.SUB_AGENT, cap.getType());
        Assert.assertEquals("data_analyst", cap.getPluginId());
        Assert.assertSame(config, cap.getSubAgentConfig());
    }

    @Test
    public void duplicateCapabilityIdShouldBeRejected() {
        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.load(new DefaultPluginManager().registerTool(weatherTool()));

        Assert.assertThrows(IllegalStateException.class,
                () -> marketplace.load(new DefaultPluginManager().registerTool(weatherTool())));
    }

    @Test
    public void blankCapabilityNameShouldBeRejected() {
        Tool blankNameTool = Tool.builder()
                .name("")
                .description("blank name tool")
                .params("")
                .func(arg -> "noop")
                .build();

        Assert.assertThrows(IllegalArgumentException.class,
                () -> new SimpleMarketplace().load(new DefaultPluginManager().registerTool(blankNameTool)));
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

    @Test
    public void withToolQuickStartAgentShouldFinish() {
        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withTool(weatherTool())
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute("Check today's Beijing weather. Is it good for running?");

        System.out.println("\n========== Example00 withTool Result ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    @Test
    public void withSkillQuickStartAgentShouldFinish() {
        SkillConfig travelAdvisor = SkillConfig.builder()
                .name("travel_advisor")
                .description("Gives outdoor-activity advice (e.g. running) based on the current weather for a city. " +
                             "TRIGGER: Use when the user asks whether the weather is suitable for an outdoor activity.")
                .systemPrompt("""
                        You are an outdoor-activity advisor.
                        1. Call get_weather for the city the user mentions.
                        2. Based on the result, give a short, direct go/no-go recommendation.
                        Keep the answer to 2-3 sentences.
                        """)
                .allowedTools(List.of("get_weather"))
                .build();

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withTool(weatherTool())
                .withSkill(travelAdvisor)
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute(
                "I want to go for a run in Beijing today. Should I, and what should I watch out for?");

        System.out.println("\n========== Example00 withSkill Result ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("=================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    @Test
    public void withSubAgentQuickStartAgentShouldFinish() {
        Tool forecastTool = Tool.builder()
                .name("get_weekly_forecast")
                .description("Gets a 3-day weather outlook for a city.")
                .params("city: String -- city name")
                .func(city -> "Beijing 3-day outlook: Day1 sunny 22C, Day2 cloudy 19C, Day3 light rain 17C.")
                .build();

        SubAgentConfig forecastPlanner = SubAgentConfig.builder()
                .name("forecast_planner")
                .description("Plans which of the next 3 days is best for an outdoor run in a given city. " +
                             "TRIGGER: Use when the user wants to pick the best day in the next few days for an outdoor activity.")
                .model("inherit")
                .systemPrompt("""
                        You are a running-day planner.
                        1. Call get_weekly_forecast for the city.
                        2. Pick the single best day for a run and explain why in one sentence.
                        """)
                .ownTools(List.of(forecastTool))
                .build();

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withSubAgent(forecastPlanner)
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute("Which day in the next 3 days is best for an outdoor run in Beijing?");

        System.out.println("\n========== Example00 withSubAgent Result ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("====================================================\n");

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
