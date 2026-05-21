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

package org.salt.heliosagent.core.market.plugin;

import java.lang.annotation.*;

/**
 * Marks a class as a plugin whose {@link org.salt.jlangchain.rag.tools.annotation.AgentTool}
 * methods are registered into the Marketplace.
 *
 * <pre>{@code
 * @Plugin(id = "weather-plugin", name = "Weather Plugin",
 *         description = "天气查询", tags = {"weather"})
 * public class WeatherPlugin {
 *
 *     @AgentTool("获取指定城市今天的天气")
 *     public String getWeather(String city) { ... }
 * }
 * }</pre>
 *
 * Use {@code DefaultPluginManager.scanPackages("com.example.plugins")} to discover and
 * register all {@code @Plugin} classes in a package.
 * Classes must have a public no-arg constructor for automatic instantiation.
 * Spring-managed beans should be passed to {@code DefaultPluginManager.register(bean)} instead.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Plugin {

    String id();

    String name() default "";

    String description() default "";

    String version() default "1.0";

    String[] tags() default {};
}
