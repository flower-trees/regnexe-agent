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

package org.salt.regnexe.agent.core;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.regnexe.agent.core.common.enums.CapabilityType;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.ConsoleEventListener;
import org.salt.regnexe.agent.core.llm.DefaultModelProvider;
import org.salt.regnexe.agent.core.llm.ModelSpec;
import org.salt.regnexe.agent.core.llm.Vendor;
import org.salt.regnexe.agent.core.market.SimpleMarketplace;
import org.salt.regnexe.agent.core.market.plugin.CapabilityDescriptor;
import org.salt.regnexe.agent.core.market.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.skill.Skill;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.subagent.SubAgent;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

/**
 * Mixed-capability smoke test: business trip assistant.
 *
 * Scenario
 * --------
 * Goal: "下周去成都出差3天，帮我查天气、分析差旅报销合同条款、规划出差行程"
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
public class BusinessTripAssistantTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Autowired
    private ChainActor chainActor;

    @Test
    public void businessTripShouldFinish() {

        // ── 1. MCP Tool: get_weather ─────────────────────────────────────────

        Tool weatherTool = Tool.builder()
                .name("get_weather")
                .description("查询指定城市近期天气预报，包含温度、天气状况和出行建议。")
                .params("city: String -- 城市名称")
                .func(city -> {
                    String c = city != null ? city.toString() : "";
                    if (c.contains("成都")) {
                        return "成都近期天气：多云转阴，气温 18~25°C，湿度 70%，偶有小雨。" +
                               "建议携带雨伞和轻薄外套，上午适合户外活动，下午注意防雨。";
                    }
                    return c + "：晴，气温 20°C。";
                })
                .build();

        // ── 2. Skill: contract_analyzer (inner tool: analyze_clause) ─────────

        Tool analyzeClauseTool = Tool.builder()
                .name("analyze_clause")
                .description("分析合同条款的法律风险。输入具体条款原文。")
                .params("clause: String -- 合同条款原文")
                .func(clause -> {
                    String text = clause != null ? clause.toString() : "";
                    if (text.contains("不予报销") || text.contains("自行承担")) {
                        return "风险等级: 高\n" +
                               "问题: 免责条款过宽，可能导致合理差旅费用无法报销。\n" +
                               "建议: 明确列举不予报销的具体情形，增加申诉机制。";
                    }
                    if (text.contains("上限") || text.contains("限额")) {
                        return "风险等级: 中\n" +
                               "问题: 报销限额未随物价调整，存在覆盖不足风险。\n" +
                               "建议: 增加每年按CPI调整条款，或设置弹性审批通道。";
                    }
                    return "风险等级: 低\n问题: 条款表述清晰，无明显法律风险。\n建议: 维持现状。";
                })
                .build();

        SkillConfig contractSkillConfig = SkillConfig.builder()
                .name("contract_analyzer")
                .description("差旅合同条款法律风险分析。识别报销条款中的不公平条件和法律漏洞。" +
                             "TRIGGER: 需要分析合同、协议、报销政策条款时使用。")
                .systemPrompt("""
                        你是一个专业的差旅合同风险分析助手。
                        收到合同条款后：
                        1. 识别关键条款（报销范围、金额上限、免责条款等）
                        2. 对每个关键条款调用 analyze_clause 工具获取风险评估
                        3. 汇总各条款风险，给出总体建议
                        请用中文输出，格式：条款→风险→建议。
                        """)
                .build();

        Skill contractSkill = Skill.from(contractSkillConfig, chainActor)
                .llm(new DefaultModelProvider().provide(ModelSpec.of(Vendor.ALIYUN, "deepseek-v4-flash")))
                .tools(analyzeClauseTool)
                .build();

        // ── 3. SubAgent: travel_planner (inner tools: get_attractions, get_restaurants) ──

        Tool attractionsTool = Tool.builder()
                .name("get_attractions")
                .description("获取成都热门景点。输入主题：文化/自然/商务配套。")
                .params("theme: String -- 景点主题")
                .func(theme -> {
                    String t = theme != null ? theme.toString() : "";
                    if (t.contains("文化")) {
                        return "文化景点：武侯祠（2h）、宽窄巷子（1.5h）、锦里古街（1h）";
                    }
                    if (t.contains("自然")) {
                        return "自然景点：青城山（半天）、都江堰（3h）、浣花溪公园（1h）";
                    }
                    return "商务配套：春熙路商圈（购物/餐饮）、天府国际会议中心（会议室）、IFS国际金融中心（商务餐厅）";
                })
                .build();

        Tool restaurantsTool = Tool.builder()
                .name("get_restaurants")
                .description("获取成都商务餐厅和特色美食推荐。")
                .params("type: String -- 餐厅类型（商务/特色/快餐）")
                .func(type -> {
                    String t = type != null ? type.toString() : "";
                    if (t.contains("商务")) {
                        return "商务餐厅：银石盘（川菜，适合宴请，人均150）、万豪JW商务套餐（人均200）";
                    }
                    return "特色美食：大龙燚火锅（人均80）、廖记棒棒鸡（人均45）、赖汤圆（早餐，人均15）";
                })
                .build();

        SubAgentConfig travelAgentConfig = SubAgentConfig.builder()
                .name("travel_planner")
                .description("成都出差行程规划专家。结合出差目的和天气，安排工作会议与观光的合理时间分配。" +
                             "TRIGGER: 需要规划出差或旅行行程时使用。")
                .systemPrompt("""
                        你是一个专业的商务出差行程规划师。
                        规划原则：工作优先，观光见缝插针。
                        步骤：
                        1. 调用 get_attractions 了解周边文化/商务配套
                        2. 调用 get_restaurants 获取商务和特色餐厅
                        3. 按3天输出行程：上午/下午/晚上各安排
                        注意：结合用户提供的天气信息合理安排户外活动时间。
                        """)
                .build();

        SubAgent travelAgent = SubAgent.from(travelAgentConfig, chainActor)
                .llm(new DefaultModelProvider().provide(ModelSpec.of(Vendor.ALIYUN, "deepseek-v4-flash")))
                .tools(attractionsTool, restaurantsTool)
                .build();

        // ── Marketplace: 3 plugins, 3 capability types ───────────────────────

        PluginDescriptor weatherPlugin = PluginDescriptor.builder()
                .pluginId("weather-plugin")
                .version("1.0")
                .name("Weather Plugin")
                .description("天气查询插件")
                .capabilities(List.of(
                        CapabilityDescriptor.builder()
                                .capabilityId("get_weather")
                                .pluginId("weather-plugin")
                                .type(CapabilityType.MCP_TOOL)
                                .name("get_weather")
                                .description("查询指定城市近期天气预报，包含温度、天气状况和出行建议。")
                                .tags(List.of("weather"))
                                .tool(weatherTool)
                                .build()))
                .build();

        PluginDescriptor legalPlugin = PluginDescriptor.builder()
                .pluginId("legal-plugin")
                .version("1.0")
                .name("Legal Plugin")
                .description("合同分析插件")
                .capabilities(List.of(
                        CapabilityDescriptor.builder()
                                .capabilityId("contract_analyzer")
                                .pluginId("legal-plugin")
                                .type(CapabilityType.SKILL)
                                .name("contract_analyzer")
                                .description("差旅合同条款法律风险分析。识别报销条款中的不公平条件和法律漏洞。")
                                .tags(List.of("legal", "contract"))
                                .tool(contractSkill.asTool())
                                .build()))
                .build();

        PluginDescriptor travelPlugin = PluginDescriptor.builder()
                .pluginId("travel-plugin")
                .version("1.0")
                .name("Travel Plugin")
                .description("出差行程规划插件")
                .capabilities(List.of(
                        CapabilityDescriptor.builder()
                                .capabilityId("travel_planner")
                                .pluginId("travel-plugin")
                                .type(CapabilityType.SUB_AGENT)
                                .name("travel_planner")
                                .description("成都出差行程规划专家。结合出差目的和天气，安排工作会议与观光的合理时间分配。")
                                .tags(List.of("travel", "planning"))
                                .tool(travelAgent.asTool())
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
                "我下周要去成都出差3天，请帮我完成以下三件事：" +
                "1. 查询成都近期天气，给出穿衣和出行建议；" +
                "2. 分析我们公司的差旅报销合同条款风险：" +
                "   第2条：住宿费每晚上限500元，超出部分自行承担；" +
                "   第5条：餐饮费不予报销，员工自行承担；" +
                "3. 根据天气情况，帮我规划3天出差行程（含工作安排和餐厅推荐）。");

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
