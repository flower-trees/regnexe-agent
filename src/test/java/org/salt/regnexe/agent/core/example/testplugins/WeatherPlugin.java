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

package org.salt.regnexe.agent.core.example.testplugins;

import org.salt.regnexe.agent.core.market.plugin.Plugin;
import org.salt.jlangchain.rag.tools.annotation.AgentTool;

/**
 * Sample @Plugin class used by Example05PluginLoadingTest.scanPackagesShouldLoadDescriptors.
 * Must have a public no-arg constructor so DefaultPluginManager can instantiate it.
 */
@Plugin(
    id          = "test-weather-plugin",
    name        = "Test Weather Plugin",
    description = "Test weather query plugin",
    version     = "1.0",
    tags        = {"weather", "test"}
)
public class WeatherPlugin {

    @AgentTool("Gets today's weather for a given city, including temperature and outdoor activity advice.")
    public String getWeather(String city) {
        String c = city != null ? city : "";
        if (c.contains("Beijing")) {
            return "Beijing today: sunny, 22°C, excellent air quality, very suitable for outdoor running.";
        }
        return c + ": cloudy, 18°C. Reduce outdoor activity.";
    }

    @AgentTool("Gets clothing advice based on temperature.")
    public String getDressAdvice(String temperature) {
        return "Temperature " + temperature + "°C: wear a light jacket and watch the morning-evening temperature gap.";
    }
}
