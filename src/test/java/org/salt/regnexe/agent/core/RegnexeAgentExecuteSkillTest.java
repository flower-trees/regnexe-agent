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

package org.salt.regnexe.agent.core;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.memory.ConversationMemory;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityType;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.AgentEvent;
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.agent.core.llm.Vendor;
import org.salt.regnexe.agent.core.marketplace.loader.DefaultPluginManager;
import org.salt.regnexe.agent.core.marketplace.SimpleMarketplace;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Covers {@link RegnexeAgent#executeSkill} — direct skill invocation bypassing
 * Search/Plan/Reflect.
 *
 * <p>Tests 1–2 are pure validation (no LLM call). Tests 3–4 run a real Skill against a live
 * model (needs DASHSCOPE_API_KEY, same prerequisite as Example05PluginLoadingTest's
 * integration tests).
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class RegnexeAgentExecuteSkillTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    // ── Test 1: unknown capabilityId ──────────────────────────────────────────

    @Test
    public void unknownCapabilityIdShouldThrow() {
        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .build();

        try {
            agent.executeSkill("no-such-plugin.no-such-skill", "hi", null, null);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Unknown skill"));
        }
    }

    // ── Test 2: capabilityId resolves to a non-SKILL capability ───────────────

    @Test
    public void nonSkillCapabilityIdShouldThrow() {
        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.install(PluginDescriptor.builder()
                .pluginId("tools-only")
                .name("Tools Only")
                .description("d")
                .capabilities(List.of(CapabilityDescriptor.builder()
                        .capabilityId("tools-only.noop")
                        .pluginId("tools-only")
                        .type(CapabilityType.MCP_TOOL)
                        .tool(org.salt.jlangchain.rag.tools.Tool.builder()
                                .name("noop").description("does nothing").params("x: String")
                                .func(x -> "ok")
                                .build())
                        .build()))
                .build());

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPluginMarket(marketplace)
                .build();

        try {
            agent.executeSkill("tools-only.noop", "hi", null, null);
            Assert.fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            Assert.assertTrue(e.getMessage().contains("Not a skill capability"));
        }
    }

    // ── Test 3: real skill run — events, result, no history when sessionId == null ──

    @Test
    public void executeSkillShouldRunAndDispatchEventsWithoutSessionId() throws IOException {
        Path baseDir = buildTempSkillPluginDir();
        try {
            List<EventType> observedTypes = new ArrayList<>();
            AgentEventListener listener = event -> observedTypes.add(event.getType());

            RegnexeAgent agent = regnexeAgentBuilder
                    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                    .withDirectory(baseDir.toString())
                    .withEventListener(listener)
                    .build();

            AgentResult result = agent.executeSkill("demo.hello", "张三", null, null);

            System.out.println("\n========== executeSkill (no session) ==========");
            System.out.println("Status : " + result.getStatus());
            System.out.println("Answer :\n" + result.getFinalText());
            System.out.println("Events : " + observedTypes);
            System.out.println("================================================\n");

            Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
            Assert.assertNotNull(result.getFinalText());
            Assert.assertFalse(result.getFinalText().isBlank());
            Assert.assertNull("executeSkill does not build a TaskExecutionState", result.getState());

            Assert.assertEquals(EventType.AGENT_STARTED, observedTypes.get(0));
            Assert.assertEquals(EventType.AGENT_COMPLETED, observedTypes.get(observedTypes.size() - 1));
            Assert.assertTrue("TokenAggregatingEventListener should emit TASK_TOKEN_SUMMARY before AGENT_COMPLETED",
                    observedTypes.contains(EventType.TASK_TOKEN_SUMMARY));
        } finally {
            deleteTree(baseDir);
        }
    }

    // ── Test 4: sessionId set → turn gets stored via ConversationMemory ───────

    @Test
    public void executeSkillShouldStoreSessionRoundWhenSessionIdProvided() throws IOException {
        Path baseDir = buildTempSkillPluginDir();
        try {
            RecordingConversationMemory memory = new RecordingConversationMemory();

            RegnexeAgent agent = regnexeAgentBuilder
                    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                    .withDirectory(baseDir.toString())
                    .withSessionMemory(memory)
                    .build();

            AgentResult result = agent.executeSkill("demo.hello", "李四", "session-1", "/demo.hello 李四");

            Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
            Assert.assertEquals("exactly one turn should be stored", 1, memory.stored.size());
            HistoryInfos turn = memory.stored.get(0);
            Assert.assertEquals(2, turn.getMessages().size());
            Assert.assertEquals("/demo.hello 李四", turn.getMessages().get(0).getContent());
        } finally {
            deleteTree(baseDir);
        }
    }

    // ── Test 5: withClaudeCompatWorkspace routes the skill's fs tools to a real, given dir ──

    @Test
    public void claudeCompatWorkspace_routesSkillFileOpsToGivenDirectory_notAnAnonymousTempDir() throws IOException {
        Path pluginBaseDir = buildTempClaudeCompatSkillPluginDir();
        Path explicitWorkspace = Files.createTempDirectory("regnexe-explicit-workspace-");
        try {
            RegnexeAgent agent = regnexeAgentBuilder
                    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                    .withDirectory(pluginBaseDir.toString())
                    .withClaudeCompatWorkspace(explicitWorkspace)
                    .build();

            AgentResult result = agent.executeSkill("claude-compat-demo.filewriter",
                    "write a file named note.txt containing exactly: hello", null, null);

            System.out.println("\n========== claude-compat workspace routing ==========");
            System.out.println("Status : " + result.getStatus());
            System.out.println("Answer :\n" + result.getFinalText());
            System.out.println("=======================================================\n");

            Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
            Assert.assertTrue("note.txt must land in the explicitly-configured workspace, not some "
                            + "anonymous system temp dir",
                    Files.exists(explicitWorkspace.resolve("note.txt")));
        } finally {
            deleteTree(pluginBaseDir);
            deleteTree(explicitWorkspace);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * <pre>
     * {tmpDir}/
     *   demo/
     *     plugin.yaml
     *     skills/
     *       hello/
     *         SKILL.md
     * </pre>
     */
    private Path buildTempSkillPluginDir() throws IOException {
        Path baseDir = Files.createTempDirectory("regnexe-executeskill-test-");
        Path pluginDir = baseDir.resolve("demo");
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("plugin.yaml"),
                "pluginId: demo\nname: Demo Plugin\ndescription: smoke test plugin\nversion: \"1.0\"\n");

        Path skillDir = pluginDir.resolve("skills").resolve("hello");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: hello
                description: "Greets the user by name. TRIGGER: use for simple greetings."
                ---
                You are a simple greeter. Given the user's name, reply with one short sentence
                greeting them by name. Do not call any tools.
                """);
        return baseDir;
    }

    /**
     * <pre>
     * {tmpDir}/
     *   claude-compat-demo/
     *     plugin.yaml
     *     skills/
     *       filewriter/
     *         SKILL.md   ← no allowed-tools declared, so it only gets tools if
     *                      claude-compat mode kicks in and grants the scoped fs tools
     * </pre>
     */
    private Path buildTempClaudeCompatSkillPluginDir() throws IOException {
        Path baseDir = Files.createTempDirectory("regnexe-claudecompat-test-");
        Path pluginDir = baseDir.resolve("claude-compat-demo");
        Files.createDirectories(pluginDir);
        Files.writeString(pluginDir.resolve("plugin.yaml"),
                "pluginId: claude-compat-demo\nname: Claude Compat Demo\ndescription: smoke test plugin\nversion: \"1.0\"\n");

        Path skillDir = pluginDir.resolve("skills").resolve("filewriter");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: filewriter
                description: "Writes a file the user asks for into the workspace. TRIGGER: use when asked to write a file."
                ---
                You write files the user asks for using the write_file tool, into the workspace root
                (no subdirectory unless asked). Confirm what you wrote when done.
                """);
        return baseDir;
    }

    private void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
    }

    private static class RecordingConversationMemory implements ConversationMemory {
        final List<HistoryInfos> stored = new ArrayList<>();

        @Override
        public void storeHistory(HistoryInfos historyInfos) {
            stored.add(historyInfos);
        }

        @Override
        public List<HistoryInfos> readHistory() {
            return stored;
        }
    }
}
