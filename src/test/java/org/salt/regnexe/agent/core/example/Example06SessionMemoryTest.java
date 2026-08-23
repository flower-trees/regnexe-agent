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
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityType;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.ConsoleEventListener;
import org.salt.regnexe.agent.core.llm.Vendor;
import org.salt.regnexe.agent.core.marketplace.SimpleMarketplace;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.salt.regnexe.agent.core.task.state.TaskRequest;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.storage.InMemoryConversationStorage;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.UUID;

/**
 * Example 06: session-memory smoke test.
 *
 * Scenario
 * --------
 * Two sequential execute() calls share the same sessionId and a custom
 * InMemoryConversationStorage. After the first call completes, the storage
 * must contain a history entry. The second call should succeed, and a follow-up
 * question that relies on prior context ("based on the weather advice from before") is answered
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
public class Example06SessionMemoryTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    @Test
    public void sessionHistoryShouldPersistAcrossExecutions() {

        // ── Shared session storage (injected so the test can inspect it) ─────

        InMemoryConversationStorage sessionStorage = new InMemoryConversationStorage();
        String sessionId = UUID.randomUUID().toString();

        // ── Deterministic weather tool ────────────────────────────────────────

        Tool weatherTool = Tool.builder()
                .name("get_weather")
                .description("Gets today's weather for a given city, including temperature and exercise advice.")
                .params("city: String -- city name")
                .func(city -> {
                    String c = city != null ? city.toString() : "";
                    if (c.contains("Beijing")) {
                        return "Beijing today: sunny, 22°C, excellent air quality, very suitable for outdoor running.";
                    }
                    return c + ": cloudy, 18°C. Reduce outdoor activity.";
                })
                .build();

        CapabilityDescriptor weatherCap = CapabilityDescriptor.builder()
                .capabilityId("get_weather")
                .pluginId("weather-plugin")
                .type(CapabilityType.MCP_TOOL)
                .tags(List.of("weather"))
                .tool(weatherTool)
                .build();

        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.install(PluginDescriptor.builder()
                .pluginId("weather-plugin").version("1.0")
                .name("Weather Plugin").description("Weather query")
                .capabilities(List.of(weatherCap))
                .build());

        // ── Agent with shared session storage ─────────────────────────────────

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPluginMarket(marketplace)
                .withSessionStorage(sessionStorage)
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        // ── Execute 1: fetch weather ──────────────────────────────────────────

        TaskRequest req1 = new TaskRequest();
        req1.setGoal("Check today's weather in Beijing and tell me whether it is suitable for outdoor running.");
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
        req2.setGoal("Based on the Beijing weather you checked earlier, what should I watch out for if I go running today? Do not check the weather again; just give advice.");
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
