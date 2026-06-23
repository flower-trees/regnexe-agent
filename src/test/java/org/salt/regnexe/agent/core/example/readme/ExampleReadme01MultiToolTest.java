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

/**
 * README — Getting Started: multiple tools via {@code withTool(...)}.
 *
 * Registers two pre-built {@link Tool} objects directly (no {@code @Plugin} class needed)
 * and lets the agent's Search -> Plan -> Execute -> Reflect loop decide how to combine them.
 *
 * Prerequisites: set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class ExampleReadme01MultiToolTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void multiToolGoalShouldFinish() {
        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withTool(weatherTool(), airQualityTool())
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute(
                "Check today's weather and air quality in Beijing, then tell me if it's good for outdoor running.");

        System.out.println("\n========== ExampleReadme01 MultiTool Result ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("=======================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    private Tool weatherTool() {
        return Tool.builder()
                .name("get_weather")
                .description("Get today's weather for a city.")
                .params("city: String -- city name")
                .func(city -> "Beijing: sunny, 22 C.")
                .build();
    }

    private Tool airQualityTool() {
        return Tool.builder()
                .name("get_air_quality")
                .description("Get today's air quality index (AQI) for a city.")
                .params("city: String -- city name")
                .func(city -> "Beijing: AQI 35, excellent air quality.")
                .build();
    }
}
