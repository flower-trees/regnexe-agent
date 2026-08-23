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
 * Marks a class as a SUB_AGENT capability. Unlike {@link AgentSkill}, a Sub-Agent can own a
 * model independent of the parent ({@link #model()}), and any {@code @AgentTool} method on the
 * annotated class becomes one of its <b>private</b> tools — scanned the same way {@code @Plugin}
 * scans tool methods, but never exposed as a standalone marketplace capability.
 *
 * <pre>{@code
 * @AgentSubAgent(
 *     id = "expense_estimator",
 *     description = "Estimates the total cost of a business trip. " +
 *                    "TRIGGER: use when the user asks for a trip budget or cost estimate.",
 *     model = "aliyun:qwen-plus",
 *     systemPrompt = """
 *             You are a travel expense estimator.
 *             1. Call estimate_trip_cost with the trip length and destination.
 *             2. Report the total and a one-line breakdown.
 *             """
 * )
 * public class ExpenseEstimatorSubAgent {
 *
 *     @AgentTool("Estimates total cost for a multi-day business trip.")
 *     public String estimateTripCost(int days, String city) { ... }
 * }
 * }</pre>
 *
 * Use {@code DefaultPluginManager.scanPackages(...)} to discover {@code @AgentSubAgent} classes
 * in a package, or {@code register(instance)} / {@code RegnexeAgentBuilder.withPlugin(instance)}
 * to register one directly. Classes must have a public no-arg constructor for automatic
 * instantiation.
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
public @interface AgentSubAgent {

    /** Capability id, used as the tool name exposed to the master LLM. */
    String id();

    String name() default "";

    /** Sub-agent description shown to the master LLM for routing. Should include TRIGGER/SKIP conditions. */
    String description();

    /**
     * Model hint. Special value {@code "inherit"} (default) means the sub-agent does not own an
     * LLM and shares the parent agent's model. Any other value (e.g. {@code "aliyun:qwen-plus"})
     * is resolved as its own model.
     */
    String model() default "inherit";

    /** System prompt for the sub-agent's internal executor. */
    String systemPrompt() default "";

    /** Capability ids of marketplace tools this sub-agent may also borrow, in addition to its own {@code @AgentTool} methods. */
    String[] allowedTools() default {};

    String[] tags() default {};

    /** Max ReAct/FC iterations for the internal executor. -1 means use the framework default. */
    int maxIterations() default -1;
}
