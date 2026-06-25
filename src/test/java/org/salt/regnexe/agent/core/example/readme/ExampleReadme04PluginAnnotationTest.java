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
import org.salt.jlangchain.rag.tools.annotation.AgentTool;
import org.salt.regnexe.agent.core.RegnexeAgent;
import org.salt.regnexe.agent.core.RegnexeAgentBuilder;
import org.salt.regnexe.agent.core.TestApplication;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.ConsoleEventListener;
import org.salt.regnexe.agent.core.llm.Vendor;
import org.salt.regnexe.agent.core.market.plugin.AgentSkill;
import org.salt.regnexe.agent.core.market.plugin.AgentSubAgent;
import org.salt.regnexe.agent.core.market.plugin.Plugin;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * README — Plugin concept: {@code @Plugin} bean registration, plus its {@code @AgentSkill} /
 * {@code @AgentSubAgent} counterparts for the two richer capability types.
 *
 * The two getting-started tools from {@link ExampleReadme01MultiToolTest} (raw {@link
 * org.salt.jlangchain.rag.tools.Tool} objects passed to {@code withTool(...)}) are re-packaged
 * here as {@code @AgentTool} methods on one {@code @Plugin}-annotated class.
 *
 * {@code @AgentSkill} and {@code @AgentSubAgent} ({@link ExampleReadme02SkillTest} / {@link
 * ExampleReadme03SubAgentTest}'s capabilities, as annotations) nest as {@code public static}
 * inner classes of that same {@code @Plugin} class, bundling everything — two tools, a skill,
 * and a sub-agent — under one {@code pluginId} via a single {@code withPlugin(new WeatherPlugin())}
 * call, exactly like the file-based {@code tools/}+{@code skills/}+{@code subagents/} layout or
 * {@code PluginDescriptor.builder().tool().skillConfig().subAgentConfig()} bundle one plugin from
 * code. {@code @AgentSkill} is a pure marker — a Skill never owns tools, so no methods are needed.
 * {@code @AgentSubAgent} reuses {@code @AgentTool} for its private {@code ownTools}, exactly like
 * the outer {@code @Plugin} does for MCP_TOOL — the only difference is the resulting capability type.
 *
 * Prerequisites: set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class ExampleReadme04PluginAnnotationTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void pluginToolsShouldFinish() {
        RegnexeAgent agent = buildAgent();

        AgentResult result = agent.execute(
                "Check today's weather and air quality in Beijing, then tell me if it's good for outdoor running.");

        System.out.println("\n========== ExampleReadme04 @Plugin tools Result ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("============================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    @Test
    public void nestedSkillShouldFinish() {
        RegnexeAgent agent = buildAgent();

        AgentResult result = agent.execute(
                "I want to go for a run in Beijing today. Should I, and what should I watch out for?");

        System.out.println("\n========== ExampleReadme04 nested @AgentSkill Result ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("=================================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    @Test
    public void nestedSubAgentShouldFinish() {
        RegnexeAgent agent = buildAgent();

        AgentResult result = agent.execute("What would a 3-day business trip to Chengdu cost?");

        System.out.println("\n========== ExampleReadme04 nested @AgentSubAgent Result ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("====================================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    /** One @Plugin registration bundles both tools, the skill, and the sub-agent below. */
    private RegnexeAgent buildAgent() {
        return regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPlugin(new WeatherPlugin())
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();
    }

    @Plugin(id = "weather", name = "Weather Plugin",
            description = "Weather, air quality, travel advice, and trip cost estimation",
            tags = {"weather"})
    public static class WeatherPlugin {

        @AgentTool("Get today's weather for a city.")
        public String getWeather(String city) {
            return "Beijing: sunny, 22 C.";
        }

        @AgentTool("Get today's air quality index (AQI) for a city.")
        public String getAirQuality(String city) {
            return "Beijing: AQI 35, excellent air quality.";
        }

        @AgentSkill(
                id = "travel_advisor",
                description = "Gives outdoor-activity advice based on the current weather for a city. " +
                              "TRIGGER: Use when the user asks whether the weather is suitable for an outdoor activity.",
                systemPrompt = """
                        You are an outdoor-activity advisor.
                        1. Call get_weather for the city the user mentions.
                        2. Based on the result, give a short, direct go/no-go recommendation.
                        """,
                allowedTools = {"weather.get_weather"}   // full capability id within this plugin
        )
        public static class TravelAdvisorSkill {
            // No @AgentTool methods — a Skill can't own private tools.
        }

        @AgentSubAgent(
                id = "expense_estimator",
                description = "Estimates the total cost of a business trip. " +
                              "TRIGGER: Use when the user asks for a trip budget or cost estimate.",
                model = "aliyun:qwen-plus",
                systemPrompt = """
                        You are a travel expense estimator.
                        1. Call estimate_trip_cost with the trip length and destination.
                        2. Report the total and a one-line breakdown.
                        """
        )
        public static class ExpenseEstimatorSubAgent {

            @AgentTool("Estimates total cost for a multi-day business trip.")
            public String estimateTripCost(int days, String city) {
                return "3-day Chengdu trip estimate: 3600 CNY total.";
            }
        }
    }
}
