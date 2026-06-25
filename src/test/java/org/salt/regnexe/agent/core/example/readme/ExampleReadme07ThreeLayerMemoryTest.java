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
import org.salt.jlangchain.core.agent.memory.SlidingWindowContext;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.storage.InMemoryConversationStorage;
import org.salt.jlangchain.rag.tools.Tool;
import org.salt.regnexe.agent.core.RegnexeAgent;
import org.salt.regnexe.agent.core.RegnexeAgentBuilder;
import org.salt.regnexe.agent.core.TestApplication;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.ConsoleEventListener;
import org.salt.regnexe.agent.core.llm.Vendor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.salt.regnexe.agent.core.task.state.RoundRecord;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.TaskRequest;
import org.salt.regnexe.agent.core.task.store.InMemoryTaskStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.UUID;

/**
 * README — Three layers of context memory, each solving a different problem:
 *
 * <pre>
 * Session memory (ConversationStorage)   "what did this user say in earlier tasks?"
 * Task ledger (TaskStore)                "what happened, round by round, in this execute()/resume()?"
 * Agent context (AgentContext)           "how much history does one tool-calling loop carry?"
 * </pre>
 *
 * Each is independently replaceable without touching the agent's main loop.
 *
 * Prerequisites: set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class ExampleReadme07ThreeLayerMemoryTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    private Tool weatherTool() {
        return Tool.builder()
                .name("get_weather")
                .description("Get today's weather for a city.")
                .params("city: String -- city name")
                .func(city -> "Beijing: sunny, 22 C, excellent air quality.")
                .build();
    }

    @Test
    public void sessionMemoryLayerShouldPersistAcrossExecutions() {
        InMemoryConversationStorage sessionStorage = new InMemoryConversationStorage();
        String sessionId = UUID.randomUUID().toString();

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withTool(weatherTool())
                .withSessionStorage(sessionStorage)   // layer 1: cross-task session history
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        TaskRequest first = new TaskRequest();
        first.setSessionId(sessionId);
        first.setGoal("Check today's weather in Beijing.");
        AgentResult result1 = agent.execute(first);
        Assert.assertEquals(TaskStatus.FINISHED, result1.getStatus());

        long longSessionId = (long) sessionId.hashCode();
        List<HistoryInfos> stored = sessionStorage.loadAll(0L, 0L, longSessionId);
        Assert.assertFalse("Session history should be stored after the first execute()", stored.isEmpty());

        TaskRequest second = new TaskRequest();
        second.setSessionId(sessionId);
        second.setGoal("Based on that weather, what should I wear today?");
        AgentResult result2 = agent.execute(second);

        Assert.assertEquals(TaskStatus.FINISHED, result2.getStatus());
        Assert.assertFalse(result2.getFinalText().isBlank());
    }

    @Test
    public void taskLedgerLayerShouldRecordEachRound() {
        InMemoryTaskStore taskStore = new InMemoryTaskStore();

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withTool(weatherTool())
                .withTaskStore(taskStore)   // layer 2: per-round execution ledger for one task
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute("Check today's weather in Beijing. Is it good for running?");
        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());

        TaskExecutionState ledger = taskStore.load(result.getTaskId())
                .orElseThrow(() -> new AssertionError("Task ledger must be persisted"));
        Assert.assertFalse(ledger.getRounds().isEmpty());

        for (RoundRecord round : ledger.getRounds()) {
            System.out.println("[Ledger] round " + round.getRoundNumber()
                    + " | search=" + (round.getSearch() != null)
                    + " | plan=" + (round.getPlan() != null)
                    + " | execution=" + (round.getExecutionResult() != null)
                    + " | reflection=" + (round.getReflection() != null));
        }
    }

    @Test
    public void agentContextLayerShouldAcceptSlidingWindowStrategy() {
        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withTool(weatherTool())
                // layer 3: how much history one Search->Plan->Execute->Reflect round's
                // internal tool-calling loop carries. Swap FullContext (default) for a
                // bounded window when tool-calling traces get long.
                .withAgentContext(SlidingWindowContext.builder().windowSize(5).build())
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute("Check today's weather in Beijing. Is it good for running?");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }
}
