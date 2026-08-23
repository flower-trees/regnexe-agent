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

package org.salt.regnexe.agent.core.marketplace.plugin;

import java.lang.annotation.*;

/**
 * Marks a class as a SKILL capability. A Skill always inherits the parent agent's model and
 * can only borrow tools already registered in the marketplace (via {@link #allowedTools()}) —
 * it never owns private tools, so the annotated class needs no {@code @AgentTool} methods.
 *
 * <pre>{@code
 * @AgentSkill(
 *     id = "travel_advisor",
 *     description = "Gives outdoor-activity advice based on weather. " +
 *                    "TRIGGER: use when the user asks if the weather suits an outdoor activity.",
 *     systemPrompt = """
 *             You are an outdoor-activity advisor.
 *             1. Call get_weather for the city the user mentions.
 *             2. Give a short, direct go/no-go recommendation.
 *             """,
 *     allowedTools = {"get_weather"}
 * )
 * public class TravelAdvisorSkill {
 * }
 * }</pre>
 *
 * Use {@code DefaultPluginManager.scanPackages(...)} to discover {@code @AgentSkill} classes in
 * a package, or {@code register(instance)} / {@code RegnexeAgentBuilder.withPlugin(instance)} to
 * register one directly. Classes must have a public no-arg constructor for automatic instantiation.
 *
 * <p>Used standalone, the class becomes its own single-capability plugin (capabilityId ==
 * pluginId == {@link #id()}). Nest it as a {@code public static} inner class of a {@code @Plugin}
 * class instead to bundle it with that plugin's tools under one {@code pluginId} — the resulting
 * capabilityId is {@code pluginId + "." + id()}, exactly like a {@code @Plugin}'s
 * {@code @AgentTool} methods.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AgentSkill {

    /** Capability id, used as the tool name exposed to the master LLM. */
    String id();

    String name() default "";

    /** Skill description shown to the master LLM for routing. Should include TRIGGER/SKIP conditions. */
    String description();

    /** Workflow instructions for the skill's internal executor. */
    String systemPrompt() default "";

    /** Capability ids of marketplace tools this skill is allowed to borrow. */
    String[] allowedTools() default {};

    String[] tags() default {};

    /** Max ReAct/FC iterations for the internal executor. -1 means use the framework default. */
    int maxIterations() default -1;
}
