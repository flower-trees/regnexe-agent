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
import org.salt.regnexe.agent.core.task.state.TaskRequest;
import org.salt.regnexe.agent.core.task.store.InMemoryTaskStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * README — Pause & Resume.
 *
 * {@code pause()} is thread-safe and can be called from any thread while a task is running.
 * The task transitions to {@code PAUSED} and persists in the configured {@code TaskStore};
 * {@code resume(sessionId, supplementInput)} picks up the most recent resumable task for that
 * session and continues with extra context.
 *
 * A {@link CountDownLatch}-coordinated slow tool guarantees {@code pause()} is called while the
 * tool is actually running, instead of racing a real network call.
 *
 * Prerequisites: set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class ExampleReadme08PauseResumeTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void pauseDuringExecutionShouldResumeToFinish() throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);
        CountDownLatch pauseSignaled = new CountDownLatch(1);

        Tool slowWeatherTool = Tool.builder()
                .name("get_weather")
                .description("Get today's weather for a city.")
                .params("city: String -- city name")
                .func(city -> {
                    toolStarted.countDown();
                    try {
                        pauseSignaled.await(15, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "Beijing: sunny, 22 C, excellent air quality.";
                })
                .build();

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withTool(slowWeatherTool)
                .withTaskStore(new InMemoryTaskStore())   // required: pause/resume needs persisted state
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        TaskRequest request = new TaskRequest();
        request.setSessionId(UUID.randomUUID().toString());
        request.setGoal("Check today's weather in Beijing. Is it good for running?");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<AgentResult> future = pool.submit(() -> agent.execute(request));

        Assert.assertTrue("Tool should have started within 30s", toolStarted.await(30, TimeUnit.SECONDS));

        agent.pause();              // safe to call from any thread
        pauseSignaled.countDown();  // let the in-flight tool call return

        AgentResult paused = future.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        Assert.assertEquals(TaskStatus.PAUSED, paused.getStatus());

        AgentResult resumed = agent.resume(request.getSessionId(), "Also factor in today's air quality index.");

        System.out.println("\n========== ExampleReadme08 Resume Result ==========");
        System.out.println("Status   : " + resumed.getStatus());
        System.out.println("Rounds   : " + resumed.getState().getCurrentRound());
        System.out.println("FinalText:\n" + resumed.getFinalText());
        System.out.println("====================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, resumed.getStatus());
        Assert.assertNotNull(resumed.getFinalText());
        Assert.assertFalse(resumed.getFinalText().isBlank());
    }
}
