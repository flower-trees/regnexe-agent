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

package org.salt.heliosagent.core;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.heliosagent.core.common.enums.CapabilityType;
import org.salt.heliosagent.core.common.enums.TaskStatus;
import org.salt.heliosagent.core.event.ConsoleEventListener;
import org.salt.heliosagent.core.llm.Vendor;
import org.salt.heliosagent.core.market.SimpleMarketplace;
import org.salt.heliosagent.core.market.plugin.CapabilityDescriptor;
import org.salt.heliosagent.core.market.plugin.PluginDescriptor;
import org.salt.heliosagent.core.task.AgentResult;
import org.salt.heliosagent.core.task.state.TaskRequest;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.storage.InMemoryConversationStorage;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.UUID;

/**
 * M1 session-memory smoke test.
 *
 * Scenario
 * --------
 * Two sequential execute() calls share the same sessionId and a custom
 * InMemoryConversationStorage. After the first call completes, the storage
 * must contain a history entry. The second call should succeed, and a follow-up
 * question that relies on prior context ("根据刚才的天气建议") is answered
 * without needing to re-query the tool.
 *
 * Verification
 * ------------
 * 1. Execute 1 finishes with FINISHED status.
 * 2. ConversationStorage has at least one history entry after execute 1.
 * 3. Execute 2 finishes with FINISHED status.
 * 4. Execute 2's finalText is not blank.
 *
 * Prerequisites
 * -------------
 * Set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class SessionMemoryTest {

    @Autowired
    private HeliosAgentBuilder heliosAgentBuilder;

    @Test
    public void sessionHistoryShouldPersistAcrossExecutions() {

        // ── Shared session storage (injected so the test can inspect it) ─────

        InMemoryConversationStorage sessionStorage = new InMemoryConversationStorage();
        String sessionId = UUID.randomUUID().toString();

        // ── Deterministic weather tool ────────────────────────────────────────

        Tool weatherTool = Tool.builder()
                .name("get_weather")
                .description("获取指定城市今天的天气，包括温度和运动建议。")
                .params("city: String -- 城市名称（中文）")
                .func(city -> {
                    String c = city != null ? city.toString() : "";
                    if (c.contains("北京")) {
                        return "北京今日：晴，22°C，空气优良，非常适合户外跑步。";
                    }
                    return c + "：多云，18°C，建议减少户外活动。";
                })
                .build();

        CapabilityDescriptor weatherCap = CapabilityDescriptor.builder()
                .capabilityId("get_weather")
                .pluginId("weather-plugin")
                .type(CapabilityType.MCP_TOOL)
                .name("get_weather")
                .description("获取指定城市今天的天气，包括温度和运动建议。")
                .tags(List.of("weather"))
                .tool(weatherTool)
                .build();

        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.install(PluginDescriptor.builder()
                .pluginId("weather-plugin").version("1.0")
                .name("Weather Plugin").description("天气查询")
                .capabilities(List.of(weatherCap))
                .build());

        // ── Agent with shared session storage ─────────────────────────────────

        HeliosAgent agent = heliosAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPluginMarket(marketplace)
                .withSessionStorage(sessionStorage)
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        // ── Execute 1: fetch weather ──────────────────────────────────────────

        TaskRequest req1 = new TaskRequest();
        req1.setGoal("查询北京今天的天气，告诉我是否适合户外跑步");
        req1.setSessionId(sessionId);

        AgentResult result1 = agent.execute(req1);

        System.out.println("\n========== Session Execute 1 ==========");
        System.out.println("Status  : " + result1.getStatus());
        System.out.println("Rounds  : " + result1.getState().getCurrentRound());
        System.out.println("Answer  :\n" + result1.getFinalText());
        System.out.println("========================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result1.getStatus());
        Assert.assertNotNull(result1.getFinalText());

        // Verify session storage has an entry (sessionId → Long hashCode)
        long longSessionId = (long) sessionId.hashCode();
        List<HistoryInfos> stored = sessionStorage.loadAll(0L, 0L, longSessionId);
        Assert.assertFalse("Session history should be stored after execute 1", stored.isEmpty());

        System.out.println("Session history entries after execute 1: " + stored.size());

        // ── Execute 2: follow-up question relying on session context ──────────
        // Goal explicitly says "no need to query again" — Planner will see the
        // session summary and should answer directly.

        TaskRequest req2 = new TaskRequest();
        req2.setGoal("根据你刚才查到的北京天气，建议我今天出门跑步应该注意什么？不需要再查天气了，直接给建议。");
        req2.setSessionId(sessionId);

        AgentResult result2 = agent.execute(req2);

        System.out.println("\n========== Session Execute 2 (follow-up) ==========");
        System.out.println("Status  : " + result2.getStatus());
        System.out.println("Rounds  : " + result2.getState().getCurrentRound());
        System.out.println("Answer  :\n" + result2.getFinalText());
        System.out.println("===================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result2.getStatus());
        Assert.assertNotNull(result2.getFinalText());
        Assert.assertFalse(result2.getFinalText().isBlank());

        // Both executions should have contributed to the session
        List<HistoryInfos> storedAfter2 = sessionStorage.loadAll(0L, 0L, longSessionId);
        System.out.println("Session history entries after execute 2: " + storedAfter2.size());
        Assert.assertTrue("Session history should grow after execute 2",
                storedAfter2.size() >= stored.size());
    }
}
