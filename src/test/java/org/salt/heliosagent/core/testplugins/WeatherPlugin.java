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

package org.salt.heliosagent.core.testplugins;

import org.salt.heliosagent.core.market.plugin.Plugin;
import org.salt.jlangchain.rag.tools.annotation.AgentTool;

/**
 * Sample @Plugin class used by PluginLoadingTest.scanPackagesShouldLoadDescriptors.
 * Must have a public no-arg constructor so DefaultPluginManager can instantiate it.
 */
@Plugin(
    id          = "test-weather-plugin",
    name        = "Test Weather Plugin",
    description = "测试用天气查询插件",
    version     = "1.0",
    tags        = {"weather", "test"}
)
public class WeatherPlugin {

    @AgentTool("获取指定城市今天的天气，包括温度和户外活动建议。")
    public String getWeather(String city) {
        String c = city != null ? city : "";
        if (c.contains("北京")) {
            return "北京今日：晴，22°C，空气优良，非常适合户外跑步。";
        }
        return c + "：多云，18°C，建议减少户外活动。";
    }

    @AgentTool("获取穿衣建议（根据温度）。")
    public String getDressAdvice(String temperature) {
        return "温度 " + temperature + "°C：建议穿轻薄外套，注意早晚温差。";
    }
}
