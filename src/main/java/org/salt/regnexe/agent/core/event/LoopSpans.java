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

package org.salt.regnexe.agent.core.event;

import java.util.function.Supplier;

/**
 * Span bookkeeping for one tool-calling loop (the execute loop, a sub-agent, a directly invoked
 * skill): which LLM call and which tool call the loop's callbacks currently belong to.
 *
 * <p>The underlying executor callbacks carry only text, no call ids, so a follow-up event is
 * paired with the most recently opened call of its kind. That is correct only while calls within
 * one loop run sequentially — see docs/design/13-event-span-model-design.md, section 3.
 */
public final class LoopSpans {

    private final Supplier<String> parent;
    private volatile String llmSpanId;
    private volatile String toolSpanId;

    /** @param parent span the loop's calls hang under; read each time a call opens */
    public LoopSpans(Supplier<String> parent) {
        this.parent = parent;
    }

    /** A loop nested in this one (a sub-agent) runs inside this loop's current tool call. */
    public LoopSpans nested() {
        return new LoopSpans(this::currentTool);
    }

    public AgentEvent llmStarted(AgentEvent event) {
        llmSpanId = SpanIds.next();
        return event.inSpan(llmSpanId, parent.get());
    }

    /** Output / token usage of the LLM call opened last. */
    public AgentEvent llmFollowUp(AgentEvent event) {
        return event.inSpan(llmSpanId, null);
    }

    public AgentEvent toolCalled(AgentEvent event) {
        toolSpanId = SpanIds.next();
        return event.inSpan(toolSpanId, parent.get());
    }

    public AgentEvent toolResult(AgentEvent event) {
        return event.inSpan(toolSpanId, null);
    }

    public String currentTool() {
        return toolSpanId;
    }
}
