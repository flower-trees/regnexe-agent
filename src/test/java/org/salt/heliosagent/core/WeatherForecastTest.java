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
import org.salt.heliosagent.core.market.SimpleMarketplace;
import org.salt.heliosagent.core.market.plugin.CapabilityDescriptor;
import org.salt.heliosagent.core.market.plugin.PluginDescriptor;
import org.salt.heliosagent.core.task.AgentResult;
import org.salt.heliosagent.core.task.DefaultResultComposer;
import org.salt.heliosagent.core.task.store.InMemoryTaskStore;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

/**
 * M0 smoke test: single-round weather query.
 *
 * Scenario
 * --------
 * Goal  : "查询北京今天的天气，告诉我今天是否适合户外跑步"
 * Tool  : get_weather — returns deterministic fake data so the test is repeatable
 * Expect: agent finishes in at most 3 rounds with FINISHED status
 *
 * Prerequisites
 * -------------
 * Set env var DASHSCOPE_API_KEY before running.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = TestApplication.class)
public class WeatherForecastTest {

    @Autowired
    private HeliosAgentBuilder heliosAgentBuilder;

    @Test
    public void weatherQueryShouldFinish() {

        // ── Marketplace: one plugin with one weather capability ──────────────

        CapabilityDescriptor weatherCap = CapabilityDescriptor.builder()
                .capabilityId("get_weather")
                .pluginId("weather-plugin")
                .type(CapabilityType.MCP_TOOL)
                .name("get_weather")
                .description("获取指定城市今天的天气，包括温度、天气状况、空气质量和运动适宜度。输入城市名称（中文）。")
                .tags(List.of("weather", "outdoor"))
                .tool(Tool.builder()
                        .name("get_weather")
                        .description("获取指定城市今天的天气")
                        .params("city: String -- 城市名称，例如：北京、上海")
                        .func(city -> {
                            String cityStr = city != null ? city.toString() : "";
                            if (cityStr.contains("北京")) {
                                return "北京今日天气：晴，气温 22°C，湿度 40%，风速 2 级，空气质量优（AQI 35）。" +
                                       "非常适合户外跑步，建议上午 7-9 点或傍晚 5-7 点出行。";
                            }
                            return cityStr + " 天气：多云，气温 18°C，轻度污染，建议减少户外剧烈运动。";
                        })
                        .build())
                .build();

        PluginDescriptor weatherPlugin = PluginDescriptor.builder()
                .pluginId("weather-plugin")
                .version("1.0")
                .name("Weather Plugin")
                .description("实时天气查询插件")
                .capabilities(List.of(weatherCap))
                .build();

        SimpleMarketplace marketplace = new SimpleMarketplace();
        marketplace.install(weatherPlugin);

        // ── Agent ────────────────────────────────────────────────────────────

        HeliosAgent agent = heliosAgentBuilder
                .withDefaultModel("qwen-plus")
                .withPluginMarket(marketplace)
                .withTaskStore(new InMemoryTaskStore())
                .withResultComposer(new DefaultResultComposer())
                .withMaxRounds(3)
                .build();

        // ── Execute ──────────────────────────────────────────────────────────

        AgentResult result = agent.execute("查询北京今天的天气，告诉我今天是否适合户外跑步");

        System.out.println("\n========== HeliosAgent Result ==========");
        System.out.println("Status   : " + result.getStatus());
        System.out.println("TaskId   : " + result.getTaskId());
        System.out.println("Rounds   : " + result.getState().getCurrentRound());
        System.out.println("FinalText:\n" + result.getFinalText());
        System.out.println("========================================\n");

        Assert.assertEquals(TaskStatus.FINISHED, result.getStatus());
        Assert.assertNotNull(result.getFinalText());
        Assert.assertFalse(result.getFinalText().isBlank());
    }
}
