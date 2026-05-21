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
import org.salt.heliosagent.core.market.DefaultPluginManager;
import org.salt.heliosagent.core.market.SimpleMarketplace;
import org.salt.heliosagent.core.market.plugin.CapabilityDescriptor;
import org.salt.heliosagent.core.market.plugin.Plugin;
import org.salt.heliosagent.core.task.AgentResult;
import org.salt.heliosagent.core.testplugins.WeatherPlugin;
import org.salt.jlangchain.rag.tools.annotation.AgentTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Plugin loading smoke tests — covers all three loading paths.
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
public class PluginLoadingTest {

    @Autowired
    private HeliosAgentBuilder heliosAgentBuilder;

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
        mgr.scanPackages("org.salt.heliosagent.core.testplugins");
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

        HeliosAgent agent = heliosAgentBuilder
                .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                .withPluginMarket(marketplace)
                .withEventListener(new ConsoleEventListener())
                .withMaxRounds(3)
                .build();

        AgentResult result = agent.execute("查询北京今天的天气，告诉我是否适合户外跑步");

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

            HeliosAgent agent = heliosAgentBuilder
                    .withDefaultModel(Vendor.ALIYUN, "deepseek-v4-flash")
                    .withPluginMarket(marketplace)
                    .withEventListener(new ConsoleEventListener())
                    .withMaxRounds(3)
                    .build();

            AgentResult result = agent.execute("查询北京今天的天气，告诉我是否适合户外跑步");

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
        Path baseDir = Files.createTempDirectory("helios-plugin-test-");
        Path pluginDir = baseDir.resolve("dir-weather-plugin");
        Files.createDirectories(pluginDir);

        // plugin.yaml
        Files.writeString(pluginDir.resolve("plugin.yaml"),
            "pluginId: dir-weather-plugin\n" +
            "name: Dir Weather Plugin\n" +
            "version: 1.0\n" +
            "description: 目录加载的天气查询插件\n" +
            "tags: [weather, test]\n");

        // tools/get_weather.sh + sidecar
        Path toolsDir = pluginDir.resolve("tools");
        Files.createDirectories(toolsDir);
        Files.writeString(toolsDir.resolve("get_weather.sh"),
            "#!/bin/bash\n" +
            "echo '北京今日：晴，22°C，空气优良，非常适合户外跑步。'\n");
        Files.writeString(toolsDir.resolve("get_weather.yaml"),
            "description: 获取指定城市今天的天气，包括温度和运动建议\n" +
            "params: \"city: String -- 城市名称\"\n" +
            "tags: [weather]\n");

        if (withSkill) {
            Path skillDir = pluginDir.resolve("skills").resolve("weather_advisor");
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"),
                "---\n" +
                "name: weather_advisor\n" +
                "description: \"根据天气情况给出户外活动建议，TRIGGER使用\"\n" +
                "---\n" +
                "你是一个天气顾问，根据用户提供的天气信息，给出合理的户外活动建议。\n" +
                "请简洁地回答，包含是否适合出行、穿衣建议、活动推荐。\n");
        }

        if (withSubAgent) {
            Path agentDir = pluginDir.resolve("subagents").resolve("outdoor_advisor");
            Files.createDirectories(agentDir);
            Files.writeString(agentDir.resolve("AGENT.md"),
                "---\n" +
                "name: outdoor_advisor\n" +
                "description: 户外活动规划子Agent，综合天气和用户偏好给出活动建议。\n" +
                "---\n" +
                "你是一个专业的户外活动规划师。\n" +
                "根据用户提供的天气信息和需求，给出详细的户外活动规划建议。\n" +
                "包括：活动类型、时间安排、注意事项。\n");
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
            description = "内联天气查询插件", tags = {"weather"})
    public static class InlineWeatherPlugin {

        @AgentTool("获取指定城市今天的天气，包括温度和运动建议。")
        public String getWeather(String city) {
            String c = city != null ? city : "";
            if (c.contains("北京")) return "北京今日：晴，22°C，空气优良，非常适合户外跑步。";
            return c + "：多云，18°C，建议减少户外活动。";
        }
    }
}
