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

package org.salt.regnexe.agent.core.task.worker;

/**
 * Shared key constants for both ContextBus (single-round) and transmitMap (cross-round).
 */
public final class ContextBusKeys {

    // --- ContextBus keys: intra-round, written and consumed within one loop iteration ---

    public static final String CANDIDATES          = "candidates";
    public static final String PLAN_NARRATIVE      = "plan_narrative";
    public static final String SELECTED_CAPS       = "selected_caps";
    public static final String CAPABILITY_INPUT_DESCS = "capability_input_descs";
    public static final String EXEC_TEXT           = "exec_text";
    public static final String EXEC_TRACE          = "exec_trace";

    // --- TransmitMap keys: cross-round, set by RegnexeAgent before the loop starts ---

    public static final String STATE          = "state";
    public static final String CHAIN_ACTOR    = "chainActor";
    public static final String LLM_PROVIDER   = "llmProvider";
    public static final String MARKETPLACE    = "marketplace";
    public static final String DEFAULT_MODEL  = "defaultModel";
    /**
     * Optional per-role model override for {@code TaskPlanner}. Falls back to
     * {@link #DEFAULT_MODEL} when unset — most deployments run everything on one model.
     * <p>
     * Rationale for splitting this out from {@link #DEFAULT_MODEL} in the first place: Planner
     * and {@code Reflector} are each a single, small, structured-JSON LLM call per round —
     * cheap to run on a stronger/pricier model regardless of round count, unlike Execute (the
     * tool-calling ReAct loop), whose cost scales with iteration count and should stay on the
     * cheaper/faster model. Reflector's FINISH verdict in particular is a one-way door: a wrong
     * FINISH ends the task with no later round to catch and correct it, unlike a Planner mistake
     * (recoverable next round) or an Execute mistake (Reflector can send it back CONTINUE) — so
     * judgment quality there has outsized leverage relative to its own small direct cost.
     */
    public static final String PLANNER_MODEL = "plannerModel";
    /** Optional per-role model override for {@code Reflector} — see {@link #PLANNER_MODEL} javadoc. */
    public static final String REFLECTOR_MODEL = "reflectorModel";
    public static final String EVENT_LISTENER = "eventListener";
    public static final String STOP_SIGNAL    = "stopSignal";
    public static final String TASK_STORE     = "taskStore";
    public static final String SESSION_SUMMARY      = "sessionSummary";
    public static final String SESSION_HISTORY      = "sessionHistory";
    public static final String AGENT_CONTEXT        = "agentContext";
    public static final String MAX_AGENT_ITERATIONS    = "maxAgentIterations";
    /**
     * The originally configured {@link #MAX_AGENT_ITERATIONS} value, set once at task start and
     * never overwritten — unlike {@link #MAX_AGENT_ITERATIONS} itself, which {@code TaskPlanner}
     * mutates per round based on that round's {@code iterationsHint}. Without this separate,
     * untouched baseline, a round that legitimately tightens the budget (e.g. a simple round
     * hinting 8 iterations against a configured default of 60) would leak that tightened ceiling
     * into every later round of the same task, even ones whose own plan needs more — each round
     * must compute its override relative to the real original default, not whatever a previous
     * round happened to leave behind. Also used to keep TaskPlanner's own prompt text (the "if
     * <= the default (N)" instruction) honest about the actual configured value instead of a
     * stale hardcoded number.
     */
    public static final String MAX_AGENT_ITERATIONS_DEFAULT = "maxAgentIterationsDefault";
    /** Caps consecutive tool-call failures before {@code McpAgentExecutor} aborts the round
     * early instead of grinding through {@link #MAX_AGENT_ITERATIONS} retrying the same broken
     * dependency — see {@code RegnexeAgentBuilder.withMaxConsecutiveToolFailures} javadoc. */
    public static final String MAX_CONSECUTIVE_TOOL_FAILURES = "maxConsecutiveToolFailures";
    public static final String MAX_CONTEXT_OUTPUT_CHARS = "maxContextOutputChars";
    public static final String VERBOSE                 = "verbose";
    public static final String CLAUDE_COMPAT_WORKSPACE = "claudeCompatWorkspace";
    /**
     * Long-term project memory (REX.md content). Independent of the three memory layers
     * (Session/Task/AgentContext): not scoped to a sessionId, always present once configured,
     * read by both TaskPlanner and CapabilityExecutor.
     */
    public static final String PROJECT_MEMORY          = "projectMemory";
    /**
     * Names of the "base" tools registered directly via {@code RegnexeAgentBuilder.withTool(...)}
     * (e.g. regnexe-cli's FileTools/BashTool). Unlike marketplace-discovered capabilities, these
     * are not subject to per-round Planner selection: a selected SKILL capability always gets
     * access to all of them, because a Skill shares the main agent's full tool access regardless
     * of its own {@code allowedTools} declaration — that field only names extra tools to resolve
     * on top of the base set, it does not restrict access. Set of tool names ({@code Set<String>});
     * may be absent/empty if no base tools were registered.
     */
    public static final String BASE_TOOL_NAMES         = "baseToolNames";

    private ContextBusKeys() {}
}
