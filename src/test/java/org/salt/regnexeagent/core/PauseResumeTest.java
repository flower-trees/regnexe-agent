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
import org.salt.regnexeagent.core.llm.Vendor;
import org.salt.regnexeagent.core.market.SimpleMarketplace;
import org.salt.regnexeagent.core.market.plugin.CapabilityDescriptor;
import org.salt.regnexeagent.core.market.plugin.PluginDescriptor;
import org.salt.regnexeagent.core.task.AgentResult;
import org.salt.regnexeagent.core.task.state.TaskExecutionState;
import org.salt.regnexeagent.core.task.state.TaskRequest;
import org.salt.regnexeagent.core.task.store.InMemoryTaskStore;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * M1 pause / resume smoke tests.
 *
 * Test 1 — manualPausedStateShouldResume
 * ----------------------------------------
 * Inject a pre-built PAUSED TaskExecutionState into a custom InMemoryTaskStore,
 * then call agent.resume(). Verifies that resume() correctly picks up the task
 * and runs it to FINISHED without threading gymnastics.
 *
 * Test 2 — pauseDuringExecutionShouldTransitionToPaused
 * -------------------------------------------------------
 * Uses a cooperative slow tool controlled by CountDownLatch to ensure the tool
 * is running before pause() is called. After the tool returns, shouldContinue()
 * detects the stop signal and throws AgentStoppedException → PAUSED.
 * Then resume() with a supplementInput re-runs the task to FINISHED.
 *
 * Prerequisites
 * -------------
 * Set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class PauseResumeTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    // ── Shared tool / marketplace builder ────────────────────────────────────

    private SimpleMarketplace buildMarketplace(Tool weatherTool) {
        CapabilityDescriptor cap = CapabilityDescriptor.builder()
                .capabilityId("get_weather")
                .pluginId("weather-plugin")
                .type(CapabilityType.MCP_TOOL)
                .name("get_weather")
                .description("获取指定城市今天的天气，包括温度和运动建议。输入城市名称（中文）。")
                .tags(List.of("weather"))
                .tool(weatherTool)
                .build();

        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.install(PluginDescriptor.builder()
                .pluginId("weather-plugin").version("1.0")
                .name("Weather Plugin").description("天气查询")
                .capabilities(List.of(cap))
                .build());
        return marketplace;
    }

    // ── Test 1: resume a manually-inserted PAUSED task ───────────────────────

    /**
     * Inserts a PAUSED task directly into the task store and verifies that
     * resume() runs it to completion. This isolates the resume mechanics from
     * the pause/stop signal path.
     */
    @Test
    public void manualPausedStateShouldResume() {

        Tool weatherTool = Tool.builder()
                .name("get_weather")
                .description("获取指定城市今天的天气")
                .params("city: String -- 城市名称")
                .func(city -> "北京今日：晴，22°C，空气优良，非常适合户外跑步。")
                .build();

        InMemoryTaskStore taskStore = new InMemoryTaskStore();

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPluginMarket(buildMarketplace(weatherTool))
                .withTaskStore(taskStore)
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        // Build a PAUSED task (zero rounds completed so far)
        String sessionId = UUID.randomUUID().toString();
        TaskRequest request = new TaskRequest();
        request.setGoal("查询北京今天的天气，告诉我是否适合户外跑步");
        request.setSessionId(sessionId);

        TaskExecutionState pausedState = new TaskExecutionState();
        pausedState.setTaskId(UUID.randomUUID().toString());
        pausedState.setSessionId(sessionId);
        pausedState.setRequest(request);
        pausedState.setMaxRounds(3);
        pausedState.setCreatedAt(System.currentTimeMillis());
        pausedState.setStatus(TaskStatus.PAUSED);
        pausedState.setCurrentRound(0);
        pausedState.setUpdatedAt(System.currentTimeMillis());
        pausedState.setRounds(new ArrayList<>());
        taskStore.save(pausedState);

        // Resume with a supplement
        AgentResult result = agent.resume(sessionId, "另外请考虑今天空气质量");

        System.out.println("\n========== Manual Resume Result ==========");
        System.out.println("Status : " + result.getStatus());
        System.out.println("Rounds : " + result.getState().getCurrentRound());
        System.out.println("Answer :\n" + result.getFinalText());
        System.out.println("==========================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    // ── Test 2: pause mid-execution via stop signal ───────────────────────────

    /**
     * Uses a CountDownLatch-coordinated slow tool to guarantee pause() is
     * called while the tool is executing. After the tool returns,
     * shouldContinue() detects the stop signal → AgentStoppedException → PAUSED.
     * Then resume() re-runs the task to FINISHED.
     *
     * Coordination sequence:
     *   test thread: await toolStarted  →  agent.pause()  →  pauseSignaled.countDown()
     *   tool thread: toolStarted.countDown()  →  await pauseSignaled  →  return result
     */
    @Test
    public void pauseDuringExecutionShouldTransitionToPausedThenResume() throws Exception {

        CountDownLatch toolStarted   = new CountDownLatch(1);
        CountDownLatch pauseSignaled = new CountDownLatch(1);

        Tool slowWeatherTool = Tool.builder()
                .name("get_weather")
                .description("获取指定城市今天的天气，包括温度和运动建议。输入城市名称（中文）。")
                .params("city: String -- 城市名称")
                .func(city -> {
                    toolStarted.countDown();             // notify: tool is running
                    try {
                        pauseSignaled.await(15, TimeUnit.SECONDS); // wait for pause signal
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "北京今日：晴，22°C，空气优良，非常适合户外跑步。";
                })
                .build();

        InMemoryTaskStore taskStore = new InMemoryTaskStore();
        String sessionId = UUID.randomUUID().toString();

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPluginMarket(buildMarketplace(slowWeatherTool))
                .withTaskStore(taskStore)
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        TaskRequest request = new TaskRequest();
        request.setGoal("查询北京今天的天气，告诉我是否适合户外跑步");
        request.setSessionId(sessionId);

        // ── Start execute in background ───────────────────────────────────────
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<AgentResult> future = pool.submit(() -> agent.execute(request));

        // ── Wait for tool to start, then pause ───────────────────────────────
        boolean toolReady = toolStarted.await(30, TimeUnit.SECONDS);
        Assert.assertTrue("Tool should have started within 30 s", toolReady);

        agent.pause();           // set stop signal
        pauseSignaled.countDown(); // let tool return → shouldContinue(1) will throw

        AgentResult pauseResult = future.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        System.out.println("\n========== Pause Result ==========");
        System.out.println("Status : " + pauseResult.getStatus());
        System.out.println("Rounds : " + pauseResult.getState().getCurrentRound());
        System.out.println("==================================\n");

        Assert.assertEquals(TaskStatus.PAUSED, pauseResult.getStatus());

        // ── Resume with supplement ────────────────────────────────────────────
        // pauseSignaled latch is already 0 so the slow tool returns immediately
        // on the next call — no additional coordination needed.
        AgentResult resumeResult = agent.resume(sessionId, "请同时告知空气质量情况");

        System.out.println("\n========== Resume Result ==========");
        System.out.println("Status : " + resumeResult.getStatus());
        System.out.println("Rounds : " + resumeResult.getState().getCurrentRound());
        System.out.println("Answer :\n" + resumeResult.getFinalText());
        System.out.println("===================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, resumeResult.getStatus());
        Assert.assertNotNull(resumeResult.getFinalText());
        Assert.assertFalse(resumeResult.getFinalText().isBlank());
    }
}
