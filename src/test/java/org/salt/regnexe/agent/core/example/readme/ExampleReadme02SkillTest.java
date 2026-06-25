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
import org.salt.jlangchain.core.skill.SkillConfig;
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
 * README — Skill: {@code withSkill(SkillConfig...)}.
 *
 * A Skill is a nested agent that always <b>inherits the parent model</b> (no model field on
 * {@link SkillConfig} — there is nothing to set) and can only borrow tools that are already
 * registered in the marketplace, referenced by capability id ({@code allowedTools}). Use it
 * for a focused, repeatable sub-workflow that should share infra with the main agent.
 *
 * Prerequisites: set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class ExampleReadme02SkillTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void skillShouldShareToolAndInheritModel() {
        Tool weatherTool = Tool.builder()
                .name("get_weather")
                .description("Get today's weather for a city.")
                .params("city: String -- city name")
                .func(city -> "Beijing: sunny, 22 C, excellent air quality.")
                .build();

        SkillConfig travelAdvisor = SkillConfig.builder()
                .name("travel_advisor")
                .description("Gives outdoor-activity advice based on the current weather for a city. " +
                             "TRIGGER: Use when the user asks whether the weather is suitable for an outdoor activity.")
                .systemPrompt("""
                        You are an outdoor-activity advisor.
                        1. Call get_weather for the city the user mentions.
                        2. Based on the result, give a short, direct go/no-go recommendation.
                        """)
                .allowedTools(List.of("get_weather"))   // borrowed by id, not owned
                .build();

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withTool(weatherTool)        // the tool a Skill borrows must already be in the marketplace
                .withSkill(travelAdvisor)
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute(
                "I want to go for a run in Beijing today. Should I, and what should I watch out for?");

        System.out.println("\n========== ExampleReadme02 Skill Result ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("===================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }
}
