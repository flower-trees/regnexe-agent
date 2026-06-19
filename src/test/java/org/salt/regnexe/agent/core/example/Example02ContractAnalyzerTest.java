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
import org.salt.regnexe.agent.core.common.enums.CapabilityType;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.ConsoleEventListener;
import org.salt.regnexe.agent.core.llm.Vendor;
import org.salt.regnexe.agent.core.market.SimpleMarketplace;
import org.salt.regnexe.agent.core.market.plugin.CapabilityDescriptor;
import org.salt.regnexe.agent.core.market.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

/**
 * Example 02: contract risk analysis using a Skill capability.
 *
 * Scenario
 * --------
 * Goal  : analyze legal risks in contract clauses
 * Skill : contract_analyzer — driven by a system prompt and a fake analyze_clause tool
 * Expect: agent finishes with FINISHED status and non-empty analysis
 *
 * Prerequisites
 * -------------
 * Set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class Example02ContractAnalyzerTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void contractRiskAnalysisShouldFinish() {

        // ── Fake tool: deterministic clause risk lookup ──────────────────────

        Tool analyzeClauseTool = Tool.builder()
                .name("analyze_clause")
                .description("Analyzes the legal risk level and explanation for a contract clause.")
                .params("clause: String -- original contract clause to analyze")
                .func(clause -> {
                    String text = clause != null ? clause.toString() : "";
                    if (text.contains("unilateral") || text.contains("arbitrary")) {
                        return "Risk level: High\n" +
                               "Issue: The clause grants one party unilateral rights and creates a severe imbalance.\n" +
                               "Recommendation: Add an objection right and compensation terms, or limit the scope of unilateral changes.";
                    }
                    if (text.contains("liquidated damages") || text.contains("compensation")) {
                        return "Risk level: Medium\n" +
                               "Issue: The liquidated damages clause should define the calculation method and cap.\n" +
                               "Recommendation: Add a calculation formula and a reasonable cap, such as no more than 30% of the contract value.";
                    }
                    return "Risk level: Low\nIssue: No obvious risky clause was found.\nRecommendation: Keep the current wording.";
                })
                .build();

        // ── Skill: contract_analyzer ─────────────────────────────────────────

        SkillConfig skillConfig = SkillConfig.builder()
                .name("contract_analyzer")
                .description("Professional legal risk analysis for contract clauses. Takes clause text and returns risk levels and improvement advice. " +
                             "TRIGGER: Use when the user needs contract, agreement, or clause risk analysis.")
                .systemPrompt("""
                        You are a professional legal contract risk analysis assistant.
                        The user will provide contract clauses. You need to:
                        1. Call the analyze_clause tool for each key clause.
                        2. Summarize all clause risk levels (high/medium/low).
                        3. Provide an overall risk assessment and revision advice.
                        Answer in English with clear structure.
                        """)
                .allowedTools(List.of("analyze_clause"))
                .build();

        // ── Marketplace ──────────────────────────────────────────────────────

        CapabilityDescriptor analyzeClauseCap = CapabilityDescriptor.builder()
                .capabilityId("analyze_clause")
                .pluginId("legal-plugin")
                .type(CapabilityType.MCP_TOOL)
                .tags(List.of("legal", "contract", "risk"))
                .tool(analyzeClauseTool)
                .build();

        CapabilityDescriptor contractCap = CapabilityDescriptor.builder()
                .capabilityId("contract_analyzer")
                .pluginId("legal-plugin")
                .type(CapabilityType.SKILL)
                .tags(List.of("legal", "contract", "risk"))
                .skillConfig(skillConfig)
                .build();
        Assert.assertEquals(contractCap.getSkillConfig().getName(), contractCap.getName());
        Assert.assertEquals(contractCap.getSkillConfig().getDescription(), contractCap.getDescription());

        PluginDescriptor legalPlugin = PluginDescriptor.builder()
                .pluginId("legal-plugin")
                .version("1.0")
                .name("Legal Plugin")
                .description("Legal document analysis plugin")
                .capabilities(List.of(analyzeClauseCap, contractCap))
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
                "Please analyze the legal risks in these contract clauses: " +
                "Clause 3: Party A may unilaterally modify any arbitrary clause in this contract. Party B must confirm within 5 days after written notice, otherwise consent is deemed given. " +
                "Clause 7: If Party B breaches the contract, Party B must pay liquidated damages equal to 50% of the contract value. If Party A breaches the contract, Party A owes no compensation.");

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
