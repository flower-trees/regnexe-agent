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

import lombok.extern.slf4j.Slf4j;
import org.salt.jlangchain.ai.common.param.AiTokenUsage;
import org.salt.jlangchain.core.agent.AgentTokenUsageEvent;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.regnexe.agent.core.event.AgentEvent;
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.agent.core.event.LoopSpans;

/**
 * Reports a finished Plan / Reflect LLM call: its raw output, then its token usage. The execute
 * loop gets usage from McpAgentExecutor's own onTokenUsage hook; these two phases call the model
 * directly, so without this their cost never reaches the event stream or the task token summary.
 */
@Slf4j
final class LlmCallReporter {

    private LlmCallReporter() {
    }

    /**
     * @param phase distinguishes this phase's usage from the execute loop's inside
     *              TokenAggregatingEventListener, which tracks cumulative tool calls per sub-task id
     */
    static void report(AgentEventListener listener, LoopSpans spans, String taskId, int round, String phase,
                       ChatGeneration result, long startedAt) {
        if (listener == null || result == null) return;
        try {
            dispatch(listener, spans, taskId, round, phase, result, startedAt);
        } catch (RuntimeException e) {
            // Callers invoke this inside their LLM-call try block: a listener failure must not be
            // mistaken for a failed model call and trigger a paid retry.
            log.warn("Round {}: {} LLM call reporting failed: {}", round, phase, e.getMessage());
        }
    }

    private static void dispatch(AgentEventListener listener, LoopSpans spans, String taskId, int round,
                                 String phase, ChatGeneration result, long startedAt) {
        listener.dispatch(spans.llmFollowUp(AgentEvent.of(taskId, round, EventType.LLM_OUTPUT, result.getText())));

        Object raw = result.getResponseMetadata() != null
                ? result.getResponseMetadata().get(AiTokenUsage.METADATA_KEY) : null;
        AiTokenUsage usage = raw instanceof AiTokenUsage u ? u.copy() : AiTokenUsage.empty();
        usage.incrementLlmCalls();
        listener.dispatch(spans.llmFollowUp(AgentEvent.ofTokenUsage(taskId, round, AgentTokenUsageEvent.builder()
                .taskId(taskId + ":" + phase)
                .deltaUsage(usage)
                .deltaDurationMs(Math.max(0, System.currentTimeMillis() - startedAt))
                .build())));
    }
}
