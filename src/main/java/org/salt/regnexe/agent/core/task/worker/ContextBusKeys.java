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
    public static final String EVENT_LISTENER = "eventListener";
    public static final String STOP_SIGNAL    = "stopSignal";
    public static final String TASK_STORE     = "taskStore";
    public static final String SESSION_SUMMARY      = "sessionSummary";
    public static final String SESSION_HISTORY      = "sessionHistory";
    public static final String AGENT_CONTEXT        = "agentContext";
    public static final String MAX_AGENT_ITERATIONS    = "maxAgentIterations";
    public static final String MAX_CONTEXT_OUTPUT_CHARS = "maxContextOutputChars";
    public static final String VERBOSE                 = "verbose";

    private ContextBusKeys() {}
}
