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
import org.salt.regnexe.agent.core.marketplace.loader.DefaultPluginManager;
import org.salt.regnexe.agent.core.marketplace.SimpleMarketplace;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.marketplace.plugin.Plugin;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.salt.regnexe.agent.core.example.testplugins.WeatherPlugin;
import org.salt.jlangchain.rag.tools.annotation.AgentTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Example 05: plugin loading smoke tests. Covers all three loading paths.
 *
 * Test 1 — registerBeanShouldLoadDescriptors (unit)
 *   register() scans @AgentTool methods and creates MCP_TOOL descriptors.
 *   No LLM required.
 *
 * Test 2 — scanPackagesShouldLoadDescriptors (unit)
 *   scanPackages() discovers WeatherPlugin via @Plugin and creates descriptors.
 *   No LLM required.
 *
 * Test 3 — dirPluginToolDescriptorShouldBeCorrect (unit)
 *   loadFromDirectory() creates an MCP_TOOL descriptor with tool != null.
 *   No LLM required.
 *
 * Test 4 — dirPluginSkillDescriptorShouldStoreConfig (unit)
 *   loadFromDirectory() creates a SKILL descriptor with skillConfig set and tool == null.
 *   No LLM required.
 *
 * Test 5 — dirPluginSubAgentDescriptorShouldStoreConfig (unit)
 *   loadFromDirectory() creates a SUB_AGENT descriptor with subAgentConfig set and tool == null.
 *   No LLM required.
 *
 * Test 6 — registerBeanShouldRunAgent (integration — needs DASHSCOPE_API_KEY)
 *   Full agent run via @Plugin bean. Expects FINISHED.
 *
 * Test 7 — dirPluginToolShouldRunAgent (integration — needs DASHSCOPE_API_KEY + bash)
 *   Full agent run using a shell-script tool loaded from a temp directory. Expects FINISHED.
 *
 * Prerequisites for Tests 6–7
 * ----------------------------
 * Set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class Example05PluginLoadingTest {

    @Autowired
    private RegnexeAgentBuilder regnexeAgentBuilder;

    // ── Test 1: register() → descriptor check ────────────────────────────────

    @Test
    public void registerBeanShouldLoadDescriptors() {
        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.load(
            new DefaultPluginManager().register(new WeatherPlugin())
        );

        List<CapabilityDescriptor> caps = marketplace.listEnabled().stream()
                .flatMap(p -> p.getCapabilities().stream())
                .toList();

        Assert.assertFalse("At least one capability should be registered", caps.isEmpty());
        // WeatherPlugin has 2 @AgentTool methods
        Assert.assertEquals(2, caps.size());
        caps.forEach(cap -> {
            Assert.assertEquals(CapabilityType.MCP_TOOL, cap.getType());
            Assert.assertNotNull("tool must be set for MCP_TOOL", cap.getTool());
            Assert.assertNull("skillConfig must be null for MCP_TOOL", cap.getSkillConfig());
            Assert.assertNull("subAgentConfig must be null for MCP_TOOL", cap.getSubAgentConfig());
        });

        CapabilityDescriptor weather = marketplace.resolveDescriptor("test-weather-plugin.get_weather");
        Assert.assertNotNull("get_weather capability must resolve", weather);
        Assert.assertEquals("test-weather-plugin", weather.getPluginId());

        System.out.println("[Test 1] register() descriptors: " +
            caps.stream().map(CapabilityDescriptor::getName).toList());
    }

    // ── Test 2: scanPackages() → descriptor check ─────────────────────────────

    @Test
    public void scanPackagesShouldLoadDescriptors() {
        DefaultPluginManager mgr = new DefaultPluginManager();
        mgr.scanPackages("org.salt.regnexe.agent.core.example.testplugins");
        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.load(mgr);

        List<CapabilityDescriptor> caps = marketplace.listEnabled().stream()
                .flatMap(p -> p.getCapabilities().stream())
                .toList();

        Assert.assertFalse("scanPackages should find WeatherPlugin capabilities", caps.isEmpty());
        caps.forEach(cap -> Assert.assertEquals(CapabilityType.MCP_TOOL, cap.getType()));

        System.out.println("[Test 2] scanPackages() found: " +
            caps.stream().map(CapabilityDescriptor::getName).toList());
    }

    // ── Test 3: dir plugin — MCP_TOOL descriptor ─────────────────────────────

    @Test
    public void dirPluginToolDescriptorShouldBeCorrect() throws IOException {
        Path baseDir = buildTempPluginDir(false, false);
        try {
            SimpleMarketplace marketplace = new SimpleMarketplace();
            marketplace.load(new DefaultPluginManager().addDirectory(baseDir.toString()));

            CapabilityDescriptor cap = marketplace.resolveDescriptor("dir-weather-plugin.get_weather");
            Assert.assertNotNull("MCP_TOOL descriptor must exist", cap);
            Assert.assertEquals(CapabilityType.MCP_TOOL, cap.getType());
            Assert.assertNotNull("tool must be set", cap.getTool());
            Assert.assertNull("skillConfig must be null", cap.getSkillConfig());
            Assert.assertNull("subAgentConfig must be null", cap.getSubAgentConfig());
            Assert.assertEquals("get_weather", cap.getName());

            System.out.println("[Test 3] MCP_TOOL descriptor: " + cap.getName() +
                    ", description: " + cap.getDescription());
        } finally {
            deleteTree(baseDir);
        }
    }

    // ── Test 4: dir plugin — SKILL descriptor stores SkillConfig ─────────────

    @Test
    public void dirPluginSkillDescriptorShouldStoreConfig() throws IOException {
        Path baseDir = buildTempPluginDir(true, false);
        try {
            SimpleMarketplace marketplace = new SimpleMarketplace();
            marketplace.load(new DefaultPluginManager().addDirectory(baseDir.toString()));

            CapabilityDescriptor cap = marketplace.resolveDescriptor("dir-weather-plugin.weather_advisor");
            Assert.assertNotNull("SKILL descriptor must exist", cap);
            Assert.assertEquals(CapabilityType.SKILL, cap.getType());
            Assert.assertNotNull("skillConfig must be set", cap.getSkillConfig());
            Assert.assertNull("tool must be null for file-based SKILL", cap.getTool());
            Assert.assertNull("subAgentConfig must be null", cap.getSubAgentConfig());
            Assert.assertEquals("weather_advisor", cap.getSkillConfig().getName());

            System.out.println("[Test 4] SKILL descriptor: " + cap.getSkillConfig().getName() +
                    ", systemPrompt length: " + cap.getSkillConfig().getSystemPrompt().length());
        } finally {
            deleteTree(baseDir);
        }
    }

    // ── Test 5: dir plugin — SUB_AGENT descriptor stores SubAgentConfig ───────

    @Test
    public void dirPluginSubAgentDescriptorShouldStoreConfig() throws IOException {
        Path baseDir = buildTempPluginDir(false, true);
        try {
            SimpleMarketplace marketplace = new SimpleMarketplace();
            marketplace.load(new DefaultPluginManager().addDirectory(baseDir.toString()));

            CapabilityDescriptor cap = marketplace.resolveDescriptor("dir-weather-plugin.outdoor_advisor");
            Assert.assertNotNull("SUB_AGENT descriptor must exist", cap);
            Assert.assertEquals(CapabilityType.SUB_AGENT, cap.getType());
            Assert.assertNotNull("subAgentConfig must be set", cap.getSubAgentConfig());
            Assert.assertNull("tool must be null for file-based SUB_AGENT", cap.getTool());
            Assert.assertNull("skillConfig must be null", cap.getSkillConfig());
            Assert.assertEquals("outdoor_advisor", cap.getSubAgentConfig().getName());

            System.out.println("[Test 5] SUB_AGENT descriptor: " + cap.getSubAgentConfig().getName() +
                    ", systemPrompt length: " + cap.getSubAgentConfig().getSystemPrompt().length());
        } finally {
            deleteTree(baseDir);
        }
    }

    // ── Test 6: register() → full agent run ──────────────────────────────────

    @Test
    public void registerBeanShouldRunAgent() {
        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.load(
            new DefaultPluginManager().register(new WeatherPlugin())
        );

        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPluginMarket(marketplace)
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute("Check today's weather in Beijing and tell me whether it is suitable for outdoor running.");

        System.out.println("\n========== register() Agent Result ==========");
        System.out.println("Status : " + result.getStatus());
        System.out.println("Rounds : " + result.getState().getCurrentRound());
        System.out.println("Answer :\n" + result.getFinalText());
        System.out.println("=============================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    // ── Test 7: dir plugin shell script tool → full agent run ─────────────────

    @Test
    public void dirPluginToolShouldRunAgent() throws IOException {
        Path baseDir = buildTempPluginDir(false, false);
        try {
            SimpleMarketplace marketplace = new SimpleMarketplace();
            marketplace.load(new DefaultPluginManager().addDirectory(baseDir.toString()));

            RegnexeAgent agent = regnexeAgentBuilder
                    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                    .withPluginMarket(marketplace)
                    .withEventListener(new ConsoleEventListener())
                    .withMaxRounds(3)
                    .build();

            AgentResult result = agent.execute("Check today's weather in Beijing and tell me whether it is suitable for outdoor running.");

            System.out.println("\n========== dir plugin Agent Result ==========");
            System.out.println("Status : " + result.getStatus());
            System.out.println("Rounds : " + result.getState().getCurrentRound());
            System.out.println("Answer :\n" + result.getFinalText());
            System.out.println("=============================================\n");

            Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
            Assert.assertNotNull(result.getFinalText());
            Assert.assertFalse(result.getFinalText().isBlank());
        } finally {
            deleteTree(baseDir);
        }
    }

    // ── Test 8: builder.withScanPackages() shortcut → full agent run ──────────

    @Test
    public void builderWithScanPackagesShouldRunAgent() {
        RegnexeAgent agent = regnexeAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withScanPackages("org.salt.regnexe.agent.core.example.testplugins")
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute("Check today's weather in Beijing and tell me whether it is suitable for outdoor running.");

        System.out.println("\n========== withScanPackages() Agent Result ==========");
        System.out.println("Status : " + result.getStatus());
        System.out.println("Rounds : " + result.getState().getCurrentRound());
        System.out.println("Answer :\n" + result.getFinalText());
        System.out.println("=====================================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }

    // ── Test 9: builder.withDirectory() shortcut → full agent run ────────────

    @Test
    public void builderWithDirectoryShouldRunAgent() throws IOException {
        Path baseDir = buildTempPluginDir(true, true);
        try {
            RegnexeAgent agent = regnexeAgentBuilder
                    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                    .withDirectory(baseDir.toString())
                    .withEventListener(new ConsoleEventListener())
                    .withMaxRounds(3)
                    .build();

            AgentResult result = agent.execute("Check today's weather in Beijing and tell me whether it is suitable for outdoor running.");

            System.out.println("\n========== withDirectory() Agent Result ==========");
            System.out.println("Status : " + result.getStatus());
            System.out.println("Rounds : " + result.getState().getCurrentRound());
            System.out.println("Answer :\n" + result.getFinalText());
            System.out.println("==================================================\n");

            Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
            Assert.assertNotNull(result.getFinalText());
            Assert.assertFalse(result.getFinalText().isBlank());
        } finally {
            deleteTree(baseDir);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Creates a temp directory with a single plugin:
     * <pre>
     * {tmpDir}/
     *   dir-weather-plugin/
     *     plugin.yaml
     *     tools/
     *       get_weather.sh
     *       get_weather.yaml
     *     skills/          (if withSkill)
     *       weather_advisor/
     *         SKILL.md
     *     subagents/       (if withSubAgent)
     *       outdoor_advisor/
     *         AGENT.md
     * </pre>
     */
    private Path buildTempPluginDir(boolean withSkill, boolean withSubAgent) throws IOException {
        Path baseDir = Files.createTempDirectory("regnexe-plugin-test-");
        Path pluginDir = baseDir.resolve("dir-weather-plugin");
        Files.createDirectories(pluginDir);

        // plugin.yaml
        Files.writeString(pluginDir.resolve("plugin.yaml"),
            "pluginId: dir-weather-plugin\n" +
            "name: Dir Weather Plugin\n" +
            "version: 1.0\n" +
            "description: Directory-loaded weather query plugin\n" +
            "tags: [weather, test]\n");

        // tools/get_weather.sh + sidecar
        Path toolsDir = pluginDir.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("get_weather.sh"),
            "#!/bin/bash\n" +
            "echo 'Beijing today: sunny, 22°C, excellent air quality, very suitable for outdoor running.'\n");
        Files.writeString(toolsDir.resolve("get_weather.yaml"),
            "description: Gets today's weather for a given city, including temperature and exercise advice\n" +
            "params: \"city: String -- city name\"\n" +
            "tags: [weather]\n");

        if (withSkill) {
            Path skillDir = pluginDir.resolve("skills").resolve("weather_advisor");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"),
                "---\n" +
                "name: weather_advisor\n" +
                "description: \"Provides outdoor activity advice based on weather. TRIGGER: use for weather-based outdoor advice.\"\n" +
                "---\n" +
                "You are a weather advisor. Based on the user's weather information, provide reasonable outdoor activity advice.\n" +
                "Answer concisely and include travel suitability, clothing advice, and activity recommendations.\n");
        }

        if (withSubAgent) {
            Path agentDir = pluginDir.resolve("subagents").resolve("outdoor_advisor");
            Files.createDirectories(agentDir);
            Files.writeString(agentDir.resolve("AGENT.md"),
                "---\n" +
                "name: outdoor_advisor\n" +
                "description: Outdoor activity planning sub-agent that combines weather and user preferences.\n" +
                "---\n" +
                "You are a professional outdoor activity planner.\n" +
                "Based on the user's weather information and requirements, provide detailed outdoor activity planning advice.\n" +
                "Include activity type, timing, and precautions.\n");
        }

        return baseDir;
    }

    private void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException ignored) {}
                });
        } catch (IOException ignored) {}
    }

    // ── Inner @Plugin class for inline tests ──────────────────────────────────

    @Plugin(id = "inline-weather-plugin", name = "Inline Weather Plugin",
            description = "Inline weather query plugin", tags = {"weather"})
    public static class InlineWeatherPlugin {

        @AgentTool("Gets today's weather for a given city, including temperature and exercise advice.")
        public String getWeather(String city) {
            String c = city != null ? city : "";
            if (c.contains("Beijing")) return "Beijing today: sunny, 22°C, excellent air quality, very suitable for outdoor running.";
            return c + ": cloudy, 18°C. Reduce outdoor activity.";
        }
    }
}
