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

package org.salt.regnexeagent.core;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.regnexeagent.core.common.enums.CapabilityType;
import org.salt.regnexeagent.core.common.enums.TaskStatus;
import org.salt.regnexeagent.core.event.ConsoleEventListener;
import org.salt.regnexeagent.core.llm.DefaultModelProvider;
import org.salt.regnexeagent.core.llm.ModelSpec;
import org.salt.regnexeagent.core.llm.Vendor;
import org.salt.regnexeagent.core.market.SimpleMarketplace;
import org.salt.regnexeagent.core.market.plugin.CapabilityDescriptor;
import org.salt.regnexeagent.core.market.plugin.PluginDescriptor;
import org.salt.regnexeagent.core.task.AgentResult;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.subagent.SubAgent;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

/**
 * M0 smoke test: travel itinerary planning using a SubAgent capability.
 *
 * Scenario
 * --------
 * Goal     : 规划成都3日游行程
 * SubAgent : travel_planner — autonomous agent with attractions + restaurant tools
 * Expect   : agent finishes with FINISHED status and a non-empty itinerary
 *
 * Prerequisites
 * -------------
 * Set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class TravelPlannerTest {

    @Autowired
    private RegnexeAgentBuilder heliosAgentBuilder;

    @Autowired
    private ChainActor chainActor;

    @Test
    public void travelItineraryShouldFinish() {

        // ── Fake tools: deterministic attraction / restaurant data ────────────

        Tool attractionsTool = Tool.builder()
                .name("get_attractions")
                .description("获取指定城市的热门景点列表。输入城市名称和主题（文化/美食/自然）。")
                .params("city: String -- 城市名称; theme: String -- 主题（文化/美食/自然）")
                .func(input -> {
                    String text = input != null ? input.toString().toLowerCase() : "";
                    if (text.contains("文化")) {
                        return "成都文化景点：\n1. 武侯祠（三国文化，建议2小时）\n" +
                               "2. 宽窄巷子（清代建筑群，建议1.5小时）\n3. 杜甫草堂（诗圣故居，建议1小时）";
                    }
                    if (text.contains("自然")) {
                        return "成都自然景点：\n1. 青城山（道教名山，建议半天）\n" +
                               "2. 都江堰（世界遗产水利工程，建议3小时）\n3. 龙泉山城市森林公园（赏花观景，建议2小时）";
                    }
                    return "成都热门景点：\n1. 大熊猫繁育研究基地（建议上午9点前入园，3小时）\n" +
                           "2. 锦里古街（民俗文化，建议1.5小时）\n3. 天府广场（城市中心，建议1小时）";
                })
                .build();

        Tool restaurantsTool = Tool.builder()
                .name("get_restaurants")
                .description("获取指定城市的特色餐厅和美食推荐。输入城市名称。")
                .params("city: String -- 城市名称")
                .func(city -> "成都特色美食推荐：\n" +
                        "早餐：赖汤圆（总府路店）— 老字号糯米汤圆，人均15元\n" +
                        "午餐：廖记棒棒鸡（春熙路店）— 正宗川味，人均45元\n" +
                        "晚餐：大龙燚火锅（建设路总店）— 成都最受欢迎火锅，人均80元，建议提前排号\n" +
                        "夜宵：玉林串串香 — 人均30元，本地人最爱")
                .build();

        // ── SubAgent: travel_planner ──────────────────────────────────────────

        SubAgentConfig subAgentConfig = SubAgentConfig.builder()
                .name("travel_planner")
                .description("成都旅行行程规划专家。给定旅行天数和偏好，自主查询景点和美食，输出详细的每日行程。" +
                             "TRIGGER: 当用户需要规划旅行行程、景点推荐、餐厅推荐时使用。")
                .systemPrompt("""
                        你是一个专业的成都旅行规划师。
                        用户会告诉你旅行天数和偏好，你需要：
                        1. 调用 get_attractions 获取各主题景点（文化/自然/综合）
                        2. 调用 get_restaurants 获取餐厅推荐
                        3. 按天规划合理的行程，注意景点距离和游览时间
                        4. 每天包含：上午景点、午餐、下午景点、晚餐安排
                        请用中文输出，格式清晰，每天一个板块。
                        """)
                .build();

        SubAgent travelAgent = SubAgent.from(subAgentConfig, chainActor)
                .llm(new DefaultModelProvider().provide(ModelSpec.of(Vendor.ALIYUN, "deepseek-v4-flash")))
                .tools(attractionsTool, restaurantsTool)
                .verbose(true)
                .build();

        // ── Marketplace ──────────────────────────────────────────────────────

        CapabilityDescriptor travelCap = CapabilityDescriptor.builder()
                .capabilityId("travel_planner")
                .pluginId("travel-plugin")
                .type(CapabilityType.SUB_AGENT)
                .name("travel_planner")
                .description("成都旅行行程规划专家。给定旅行天数和偏好，自主规划每日行程，包含景点和餐厅。")
                .tags(List.of("travel", "planning", "chengdu"))
                .tool(travelAgent.asTool())
                .build();

        PluginDescriptor travelPlugin = PluginDescriptor.builder()
                .pluginId("travel-plugin")
                .version("1.0")
                .name("Travel Plugin")
                .description("旅行规划插件")
                .capabilities(List.of(travelCap))
                .build();

        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.install(travelPlugin);

        // ── Agent ────────────────────────────────────────────────────────────

        RegnexeAgent agent = heliosAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPluginMarket(marketplace)
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        // ── Execute ──────────────────────────────────────────────────────────

        AgentResult result = agent.execute(
                "帮我规划一个成都3日游：第1天文化古迹，第2天自然风光，第3天美食体验，请给出详细的每日行程安排。");

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
