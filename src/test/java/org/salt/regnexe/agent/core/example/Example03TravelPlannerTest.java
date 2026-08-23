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
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

/**
 * Example 03: travel itinerary planning using a SubAgent capability.
 *
 * Scenario
 * --------
 * Goal     : plan a three-day Chengdu itinerary
 * SubAgent : travel_planner — autonomous agent with attractions + restaurant tools
 * Expect   : agent finishes with FINISHED status and a non-empty itinerary
 *
 * Prerequisites
 * -------------
 * Set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class Example03TravelPlannerTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void travelItineraryShouldFinish() {

        // ── Fake tools: deterministic attraction / restaurant data ────────────

        Tool attractionsTool = Tool.builder()
                .name("get_attractions")
                .description("Gets popular attractions for a given city and theme.")
                .params("city: String -- city name; theme: String -- theme (culture/food/nature)")
                .func(input -> {
                    String text = input != null ? input.toString().toLowerCase() : "";
                    if (text.contains("culture")) {
                        return "Chengdu cultural attractions:\n1. Wuhou Shrine (Three Kingdoms culture, 2 hours)\n" +
                               "2. Kuanzhai Alley (Qing-era architecture, 1.5 hours)\n3. Du Fu Thatched Cottage (poet's former residence, 1 hour)";
                    }
                    if (text.contains("nature")) {
                        return "Chengdu nature attractions:\n1. Mount Qingcheng (Taoist mountain, half day)\n" +
                               "2. Dujiangyan (World Heritage irrigation system, 3 hours)\n3. Longquan Mountain Urban Forest Park (views and flowers, 2 hours)";
                    }
                    return "Chengdu popular attractions:\n1. Chengdu Research Base of Giant Panda Breeding (enter before 9 AM, 3 hours)\n" +
                           "2. Jinli Ancient Street (folk culture, 1.5 hours)\n3. Tianfu Square (city center, 1 hour)";
                })
                .build();

        Tool restaurantsTool = Tool.builder()
                .name("get_restaurants")
                .description("Gets signature restaurants and food recommendations for a given city.")
                .params("city: String -- city name")
                .func(city -> "Chengdu food recommendations:\n" +
                        "Breakfast: Lai Tangyuan (Zongfu Road), classic glutinous rice balls, CNY 15 per person\n" +
                        "Lunch: Liaoji Bon Bon Chicken (Chunxi Road), authentic Sichuan flavor, CNY 45 per person\n" +
                        "Dinner: Dalongyi Hotpot (Jianshe Road), popular Chengdu hotpot, CNY 80 per person, queue early\n" +
                        "Late-night snack: Yulin Chuanchuan, CNY 30 per person, local favorite")
                .build();

        // ── SubAgent: travel_planner ──────────────────────────────────────────

        SubAgentConfig subAgentConfig = SubAgentConfig.builder()
                .name("travel_planner")
                .description("Chengdu travel itinerary planner. Given trip length and preferences, it looks up attractions and food and outputs a detailed daily itinerary. " +
                             "TRIGGER: Use when the user needs travel planning, attraction recommendations, or restaurant recommendations.")
                .systemPrompt("""
                        You are a professional Chengdu travel planner.
                        The user will provide trip length and preferences. You need to:
                        1. Call get_attractions for each theme (culture/nature/general).
                        2. Call get_restaurants for restaurant recommendations.
                        3. Plan a reasonable daily itinerary, considering distance and visit duration.
                        4. Include morning attraction, lunch, afternoon attraction, and dinner for each day.
                        Answer in English with one section per day.
                        """)
                .ownTools(List.of(attractionsTool, restaurantsTool))
                .build();

        // ── Marketplace ──────────────────────────────────────────────────────

        CapabilityDescriptor travelCap = CapabilityDescriptor.builder()
                .capabilityId("travel_planner")
                .pluginId("travel-plugin")
                .type(CapabilityType.SUB_AGENT)
                .tags(List.of("travel", "planning", "chengdu"))
                .subAgentConfig(subAgentConfig)
                .build();
        Assert.assertEquals(travelCap.getSubAgentConfig().getName(), travelCap.getName());
        Assert.assertEquals(travelCap.getSubAgentConfig().getDescription(), travelCap.getDescription());

        PluginDescriptor travelPlugin = PluginDescriptor.builder()
                .pluginId("travel-plugin")
                .version("1.0")
                .name("Travel Plugin")
                .description("Travel planning plugin")
                .capabilities(List.of(travelCap))
                .build();

        SimpleMarketplace marketplace = new SimpleMarketplace();
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
                "Plan a three-day Chengdu trip: day 1 for cultural sites, day 2 for nature, and day 3 for food. Provide a detailed daily itinerary.");

        System.out.println("\n========== TravelPlanner Result ==========");
        System.out.println("Status    : " + result.getStatus());
        System.out.println("Rounds    : " + result.getState().getCurrentRound());
        System.out.println("Itinerary :\n" + result.getFinalText());
        System.out.println("==========================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }
}
