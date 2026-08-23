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
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

/**
 * Example 04: mixed-capability business trip assistant.
 *
 * Scenario
 * --------
 * Goal: "I will take a three-day business trip to Chengdu next week. Check weather,
 * analyze reimbursement contract clauses, and plan the trip."
 *
 * Capabilities (3 types):
 *   get_weather       MCP_TOOL  — 1st: weather drives clothing and schedule advice
 *   contract_analyzer SKILL     — 2nd: analyse reimbursement clauses (has inner tool)
 *   travel_planner    SUB_AGENT — 3rd: plan itinerary (has inner tools, depends on weather)
 *
 * Call order is driven by the master McpAgentExecutor:
 *   get_weather → contract_analyzer(analyze_clause×2) → travel_planner(get_attractions×3, get_restaurants)
 *
 * Prerequisites
 * -------------
 * Set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class Example04BusinessTripAssistantTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void businessTripShouldFinish() {

        // ── 1. MCP Tool: get_weather ─────────────────────────────────────────

        Tool weatherTool = Tool.builder()
                .name("get_weather")
                .description("Queries a city's recent weather forecast, including temperature, conditions, and travel advice.")
                .params("city: String -- city name")
                .func(city -> {
                    String c = city != null ? city.toString() : "";
                    if (c.contains("Chengdu")) {
                        return "Recent Chengdu weather: cloudy turning overcast, 18~25°C, 70% humidity, occasional light rain. " +
                               "Bring an umbrella and a light jacket. Mornings are better for outdoor activity; watch for rain in the afternoon.";
                    }
                    return c + ": sunny, 20°C.";
                })
                .build();

        // ── 2. MCP Tool inherited by Skill: analyze_clause ───────────────────

        Tool analyzeClauseTool = Tool.builder()
                .name("analyze_clause")
                .description("Analyzes legal risks in contract clauses.")
                .params("clause: String -- original contract clause")
                .func(clause -> {
                    String text = clause != null ? clause.toString() : "";
                    if (text.contains("not reimbursed") || text.contains("self-funded")) {
                        return "Risk level: High\n" +
                               "Issue: The exclusion is too broad and may prevent reasonable business travel costs from being reimbursed.\n" +
                               "Recommendation: List specific non-reimbursable cases and add an appeal process.";
                    }
                    if (text.contains("cap") || text.contains("limit")) {
                        return "Risk level: Medium\n" +
                               "Issue: The reimbursement cap may not track cost increases and could be insufficient.\n" +
                               "Recommendation: Add annual CPI adjustment or a flexible approval path.";
                    }
                    return "Risk level: Low\nIssue: The clause is clear and has no obvious legal risk.\nRecommendation: Keep the current wording.";
                })
                .build();

        SkillConfig contractSkillConfig = SkillConfig.builder()
                .name("contract_analyzer")
                .description("Business travel contract risk analysis. Identifies unfair terms and legal gaps in reimbursement clauses. " +
                             "TRIGGER: Use when contract, agreement, or reimbursement policy clauses need analysis.")
                .systemPrompt("""
                        You are a professional business travel contract risk analysis assistant.
                        After receiving contract clauses:
                        1. Identify key clauses, including reimbursement scope, caps, and exclusions.
                        2. Call analyze_clause for each key clause.
                        3. Summarize clause risks and provide overall advice.
                        Answer in English using the format: clause -> risk -> recommendation.
                        """)
                .build();

        // ── 3. SubAgent: travel_planner (inner tools: get_attractions, get_restaurants) ──

        Tool attractionsTool = Tool.builder()
                .name("get_attractions")
                .description("Gets popular Chengdu attractions by theme.")
                .params("theme: String -- attraction theme")
                .func(theme -> {
                    String t = theme != null ? theme.toString() : "";
                    if (t.contains("culture")) {
                        return "Cultural attractions: Wuhou Shrine (2h), Kuanzhai Alley (1.5h), Jinli Ancient Street (1h)";
                    }
                    if (t.contains("nature")) {
                        return "Nature attractions: Mount Qingcheng (half day), Dujiangyan (3h), Huanhuaxi Park (1h)";
                    }
                    return "Business support: Chunxi Road district (shopping/dining), Tianfu International Convention Center (meeting rooms), IFS (business dining)";
                })
                .build();

        Tool restaurantsTool = Tool.builder()
                .name("get_restaurants")
                .description("Gets Chengdu business restaurants and signature food recommendations.")
                .params("type: String -- restaurant type (business/signature/quick service)")
                .func(type -> {
                    String t = type != null ? type.toString() : "";
                    if (t.contains("business")) {
                        return "Business restaurants: Yinshipan (Sichuan cuisine, good for hosting, CNY 150 per person), JW Marriott business set menu (CNY 200 per person)";
                    }
                    return "Signature food: Dalongyi Hotpot (CNY 80 per person), Liaoji Bon Bon Chicken (CNY 45 per person), Lai Tangyuan (breakfast, CNY 15 per person)";
                })
                .build();

        SubAgentConfig travelAgentConfig = SubAgentConfig.builder()
                .name("travel_planner")
                .description("Chengdu business trip planner. Balances meetings and sightseeing based on business goals and weather. " +
                             "TRIGGER: Use when business trip or travel itinerary planning is needed.")
                .systemPrompt("""
                        You are a professional business trip planner.
                        Principle: prioritize work and fit sightseeing into available windows.
                        Steps:
                        1. Call get_attractions for nearby culture and business support.
                        2. Call get_restaurants for business and signature restaurants.
                        3. Output a three-day itinerary with morning, afternoon, and evening sections.
                        Note: Use the user's weather information to schedule outdoor activities appropriately.
                        """)
                .ownTools(List.of(attractionsTool, restaurantsTool))
                .build();

        // ── Marketplace: 3 plugins, 3 capability types ───────────────────────

        PluginDescriptor weatherPlugin = PluginDescriptor.builder()
                .pluginId("weather-plugin")
                .version("1.0")
                .name("Weather Plugin")
                .description("Weather query plugin")
                .capabilities(List.of(
                        CapabilityDescriptor.builder()
                                .capabilityId("get_weather")
                                .pluginId("weather-plugin")
                                .type(CapabilityType.MCP_TOOL)
                                .tags(List.of("weather"))
                                .tool(weatherTool)
                                .build()))
                .build();

        PluginDescriptor legalPlugin = PluginDescriptor.builder()
                .pluginId("legal-plugin")
                .version("1.0")
                .name("Legal Plugin")
                .description("Contract analysis plugin")
                .capabilities(List.of(
                        CapabilityDescriptor.builder()
                                .capabilityId("analyze_clause")
                                .pluginId("legal-plugin")
                                .type(CapabilityType.MCP_TOOL)
                                .tags(List.of("legal", "contract"))
                                .tool(analyzeClauseTool)
                                .build(),
                        CapabilityDescriptor.builder()
                                .capabilityId("contract_analyzer")
                                .pluginId("legal-plugin")
                                .type(CapabilityType.SKILL)
                                .tags(List.of("legal", "contract"))
                                .skillConfig(contractSkillConfig)
                                .build()))
                .build();

        PluginDescriptor travelPlugin = PluginDescriptor.builder()
                .pluginId("travel-plugin")
                .version("1.0")
                .name("Travel Plugin")
                .description("Business trip planning plugin")
                .capabilities(List.of(
                        CapabilityDescriptor.builder()
                                .capabilityId("travel_planner")
                                .pluginId("travel-plugin")
                                .type(CapabilityType.SUB_AGENT)
                                .tags(List.of("travel", "planning"))
                                .subAgentConfig(travelAgentConfig)
                                .build()))
                .build();

        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.install(weatherPlugin);
        marketplace.install(legalPlugin);
        marketplace.install(travelPlugin);

        // ── Agent ────────────────────────────────────────────────────────────

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPluginMarket(marketplace)
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        // ── Execute ──────────────────────────────────────────────────────────

        AgentResult result = agent.execute(
                "I will take a three-day business trip to Chengdu next week. Please help with three tasks: " +
                "1. Check recent Chengdu weather and provide clothing and travel advice; " +
                "2. Analyze risks in our business travel reimbursement clauses: " +
                "   Clause 2: Hotel reimbursement is capped at CNY 500 per night, and any excess is self-funded; " +
                "   Clause 5: Meal expenses are not reimbursed and are self-funded by employees; " +
                "3. Based on the weather, plan a three-day business trip itinerary with work arrangements and restaurant recommendations.");

        System.out.println("\n========== BusinessTripAssistant Result ==========");
        System.out.println("Status : " + result.getStatus());
        System.out.println("Rounds : " + result.getState().getCurrentRound());
        System.out.println("Result :\n" + result.getFinalText());
        System.out.println("==================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }
}
