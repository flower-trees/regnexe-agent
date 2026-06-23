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
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.regnexe.agent.core.RegnexeAgent;
import org.salt.regnexe.agent.core.RegnexeAgentBuilder;
import org.salt.regnexe.agent.core.TestApplication;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.ConsoleEventListener;
import org.salt.regnexe.agent.core.llm.Vendor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

/**
 * README — Sub-Agent: {@code withSubAgent(SubAgentConfig...)}.
 *
 * Unlike a Skill, a Sub-Agent can own a <b>different model</b> from the parent
 * ({@code model("aliyun:qwen-plus")} below, while the master agent runs "deepseek-v4-flash"),
 * and its tools ({@code ownTools}) are <b>private</b> — never registered in the marketplace,
 * so the outer agent can never call them directly. Use it for an independent sub-task that
 * needs its own reasoning loop, its own tools, or a cheaper/faster model.
 *
 * Prerequisites: set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class ExampleReadme03SubAgentTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void subAgentShouldUseOwnModelAndPrivateTool() {
        // Private tool: only visible inside the sub-agent's own executor, via ownTools.
        Tool estimateCostTool = Tool.builder()
                .name("estimate_trip_cost")
                .description("Estimates total cost for a multi-day business trip.")
                .params("days: int -- trip length; city: String -- destination city")
                .func(input -> "3-day Chengdu trip estimate: flights 1800 CNY, hotel 1200 CNY, meals 600 CNY. Total: 3600 CNY.")
                .build();

        SubAgentConfig expenseEstimator = SubAgentConfig.builder()
                .name("expense_estimator")
                .description("Estimates the total cost of a business trip. " +
                             "TRIGGER: Use when the user asks for a trip budget or cost estimate.")
                .model("aliyun:qwen-plus")      // its own model, independent of the parent's default model
                .systemPrompt("""
                        You are a travel expense estimator.
                        1. Call estimate_trip_cost with the trip length and destination.
                        2. Report the total and a one-line breakdown.
                        """)
                .ownTools(List.of(estimateCostTool))
                .build();

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withSubAgent(expenseEstimator)
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute("What would a 3-day business trip to Chengdu cost?");

        System.out.println("\n========== ExampleReadme03 SubAgent Result ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("======================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }
}
