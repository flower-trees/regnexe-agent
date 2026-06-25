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
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.agent.core.event.Slf4jEventListener;
import org.salt.regnexe.agent.core.llm.Vendor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

/**
 * README — Observability: {@code ConsoleEventListener} (default, used throughout this README)
 * and its token/LLM filtering flags, plus {@code Slf4jEventListener} as the production swap-in
 * that routes the same events through SLF4J instead of stdout.
 *
 * Prerequisites: set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class ExampleReadme09ObservabilityTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void consoleEventListenerShouldFinish() {
        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withTool(weatherTool())
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute("Check today's weather in Beijing. Is it good for running?");

        System.out.println("\n========== ExampleReadme09 ConsoleEventListener (default) ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("======================================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    @Test
    public void consoleEventListenerShowingTokenAndLlmEventsShouldFinish() {
        // showTokenEvents=true, showLlmEvents=true — also prints TOKEN_USAGE / CAPABILITY_TOKEN_USAGE /
        // TASK_TOKEN_SUMMARY and the raw *_LLM_RESPONDED text that the default constructor suppresses.
        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withTool(weatherTool())
                .withEventListener(new ConsoleEventListener(true, true))
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute("Check today's weather in Beijing. Is it good for running?");

        System.out.println("\n========== ExampleReadme09 ConsoleEventListener (verbose) ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("=====================================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    @Test
    public void filteringFlagsShouldSuppressTokenAndLlmEvents() {
        ConsoleEventListener quiet = new ConsoleEventListener(false, false);

        Assert.assertTrue(quiet.shouldHandle(EventType.AGENT_STARTED));
        Assert.assertTrue(quiet.shouldHandle(EventType.TOOL_CALLED));
        Assert.assertFalse(quiet.shouldHandle(EventType.TOKEN_USAGE));
        Assert.assertFalse(quiet.shouldHandle(EventType.TASK_TOKEN_SUMMARY));
        Assert.assertFalse(quiet.shouldHandle(EventType.LLM_RESPONDED));

        ConsoleEventListener verbose = new ConsoleEventListener(true, true);
        Assert.assertTrue(verbose.shouldHandle(EventType.TOKEN_USAGE));
        Assert.assertTrue(verbose.shouldHandle(EventType.LLM_RESPONDED));
    }

    /** Supplementary: the same event stream, routed through SLF4J instead of stdout. */
    @Test
    public void slf4jEventListenerShouldFinish() {
        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withTool(weatherTool())
                .withEventListener(new Slf4jEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute("Check today's weather in Beijing. Is it good for running?");

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
}
