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
import org.salt.regnexe.agent.core.market.plugin.AgentSkill;
import org.salt.regnexe.agent.core.market.plugin.AgentSubAgent;
import org.salt.regnexe.agent.core.market.plugin.CapabilityDescriptor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * Example 08: annotation-driven SKILL and SUB_AGENT registration, mirroring how {@code @Plugin} +
 * {@code @AgentTool} works for MCP_TOOL, but for the two richer capability types.
 *
 * Tests 1-2 (no LLM): {@code DefaultPluginManager.register(...)} correctly converts an
 * {@code @AgentSkill} class into a SKILL descriptor and an {@code @AgentSubAgent} class
 * (with an {@code @AgentTool} method) into a SUB_AGENT descriptor whose own tool is private.
 *
 * Tests 3-4 (LLM): both run end to end via {@code regnexeAgentBuilder.withPlugin(instance)}.
 *
 * Prerequisites: Tests 3-4 need env var DASHSCOPE_API_KEY.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class Example08AnnotatedSkillSubAgentTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void annotatedSkillShouldRegisterCorrectly() {
        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.load(new DefaultPluginManager().register(new TravelAdvisorSkill()));

        CapabilityDescriptor cap = marketplace.resolveDescriptor("travel_advisor");
        Assert.assertNotNull(cap);
        Assert.assertEquals(CapabilityType.SKILL, cap.getType());
        Assert.assertNotNull(cap.getSkillConfig());
        Assert.assertTrue(cap.getSkillConfig().getAllowedTools().contains("get_weather"));
        Assert.assertFalse(cap.getSkillConfig().getSystemPrompt().isBlank());
    }

    @Test
    public void annotatedSubAgentShouldRegisterCorrectly() {
        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.load(new DefaultPluginManager().register(new ExpenseEstimatorSubAgent()));

        CapabilityDescriptor cap = marketplace.resolveDescriptor("expense_estimator");
        Assert.assertNotNull(cap);
        Assert.assertEquals(CapabilityType.SUB_AGENT, cap.getType());
        Assert.assertNotNull(cap.getSubAgentConfig());
        Assert.assertEquals("aliyun:qwen-plus", cap.getSubAgentConfig().getModel());
        Assert.assertEquals(1, cap.getSubAgentConfig().getOwnTools().size());
        Assert.assertEquals("estimate_trip_cost", cap.getSubAgentConfig().getOwnTools().get(0).getName());
    }

    @Test
    public void annotatedSkillShouldFinishViaAgent() {
        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withTool(weatherTool())
                .withPlugin(new TravelAdvisorSkill())
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute(
                "I want to go for a run in Beijing today. Should I, and what should I watch out for?");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    @Test
    public void annotatedSubAgentShouldFinishViaAgent() {
        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPlugin(new ExpenseEstimatorSubAgent())
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute("What would a 3-day business trip to Chengdu cost?");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    private Tool weatherTool() {
        return Tool.builder()
                .name("get_weather")
                .description("Get today's weather for a city.")
                .params("city: String -- city name")
                .func(city -> "Beijing: sunny, 22 C, excellent air quality.")
                .build();
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
            allowedTools = {"get_weather"}
    )
    public static class TravelAdvisorSkill {
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
