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
import org.salt.jlangchain.core.skill.Skill;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

/**
 * M0 smoke test: contract risk analysis using a Skill capability.
 *
 * Scenario
 * --------
 * Goal  : 分析合同条款的法律风险
 * Skill : contract_analyzer — driven by a system prompt and a fake analyze_clause tool
 * Expect: agent finishes with FINISHED status and non-empty analysis
 *
 * Prerequisites
 * -------------
 * Set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class ContractAnalyzerTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Autowired
    private ChainActor chainActor;

    @Test
    public void contractRiskAnalysisShouldFinish() {

        // ── Fake tool: deterministic clause risk lookup ──────────────────────

        Tool analyzeClauseTool = Tool.builder()
                .name("analyze_clause")
                .description("分析合同条款的法律风险等级和说明。输入合同条款原文。")
                .params("clause: String -- 需要分析的合同条款原文")
                .func(clause -> {
                    String text = clause != null ? clause.toString() : "";
                    if (text.contains("单方面") || text.contains("任意")) {
                        return "风险等级: 高\n" +
                               "问题: 该条款赋予甲方单方面权利，严重失衡。\n" +
                               "建议: 增加乙方异议权和赔偿条款，或限制甲方修改范围。";
                    }
                    if (text.contains("违约金") || text.contains("赔偿")) {
                        return "风险等级: 中\n" +
                               "问题: 违约金条款需明确计算方式和上限。\n" +
                               "建议: 补充违约金计算公式，设置合理上限（不超过合同总额的30%）。";
                    }
                    return "风险等级: 低\n问题: 未发现明显风险条款。\n建议: 保持当前表述。";
                })
                .build();

        // ── Skill: contract_analyzer ─────────────────────────────────────────

        SkillConfig skillConfig = SkillConfig.builder()
                .name("contract_analyzer")
                .description("专业合同条款法律风险分析技能。输入合同条款文本，输出风险等级和改进建议。" +
                             "TRIGGER: 当用户需要分析合同、协议、条款风险时使用。")
                .systemPrompt("""
                        你是一个专业的法律合同风险分析助手。
                        用户会提供合同条款，你需要：
                        1. 调用 analyze_clause 工具对每个关键条款进行风险分析
                        2. 汇总所有条款的风险等级（高/中/低）
                        3. 给出总体风险评估和修改建议
                        请用中文回答，格式清晰，条理分明。
                        """)
                .build();

        Skill contractSkill = Skill.from(skillConfig, chainActor)
                .llm(new DefaultModelProvider().provide(ModelSpec.of(Vendor.ALIYUN, "deepseek-v4-flash")))
                .tools(analyzeClauseTool)
                .build();

        // ── Marketplace ──────────────────────────────────────────────────────

        CapabilityDescriptor contractCap = CapabilityDescriptor.builder()
                .capabilityId("contract_analyzer")
                .pluginId("legal-plugin")
                .type(CapabilityType.SKILL)
                .name("contract_analyzer")
                .description("专业合同条款法律风险分析技能。输入合同条款文本，输出风险等级和改进建议。")
                .tags(List.of("legal", "contract", "risk"))
                .tool(contractSkill.asTool())
                .build();

        PluginDescriptor legalPlugin = PluginDescriptor.builder()
                .pluginId("legal-plugin")
                .version("1.0")
                .name("Legal Plugin")
                .description("法律文件分析插件")
                .capabilities(List.of(contractCap))
                .build();

        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.install(legalPlugin);

        // ── Agent ────────────────────────────────────────────────────────────

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPluginMarket(marketplace)
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        // ── Execute ──────────────────────────────────────────────────────────

        AgentResult result = agent.execute(
                "请分析以下合同条款的法律风险：" +
                "第3条：甲方有权单方面修改本合同任意条款，乙方在收到书面通知后5日内需确认，否则视为同意。" +
                "第7条：乙方违约需支付合同总额50%的违约金，甲方违约无需赔偿。");

        System.out.println("\n========== ContractAnalyzer Result ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("Analysis :\n" + result.getFinalText());
        System.out.println("=============================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }
}
