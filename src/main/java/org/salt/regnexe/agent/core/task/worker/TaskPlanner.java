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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.salt.function.flow.FlowInstance;
import org.salt.function.flow.context.IContextBus;
import org.salt.function.flow.node.FlowNode;
import org.salt.regnexe.agent.core.event.AgentEvent;
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.agent.core.llm.ModelProvider;
import org.salt.regnexe.agent.core.llm.ModelSpec;
import org.salt.regnexe.agent.core.task.state.RoundRecord;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.capability.CapabilityCandidate;
import org.salt.regnexe.agent.core.task.state.plan.PlanOutput;
import org.salt.regnexe.agent.core.task.state.reflection.ReflectionHint;
import org.salt.regnexe.agent.core.task.store.TaskStore;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.chat.ChatPromptTemplate;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reads candidates from Searcher and produces a PlanOutput
 * (narrative + selectedCapabilityIds) for Executor.
 */
@Slf4j
public class TaskPlanner extends FlowNode<Object, Object> implements Worker {

    private static final String SYSTEM_PROMPT = """
            You are a task planner. Your job is to read the user's goal, the available capabilities, \
            and any prior execution history, then produce a concise execution plan.

            Rules:
            - Select only capabilities that are genuinely relevant to the goal.
            - The narrative should be clear, actionable instructions for the executor.
            - CRITICAL: every capability name you mention in the narrative MUST also appear in selectedCapabilityIds.
            - For each selected capability, write a focused input description in capabilityInputDescriptions: \
              describe what context to pass when invoking it (e.g. user goal, specific data, or the output \
              of a preceding capability). Be specific — the executor uses these descriptions to construct \
              the actual input and will not re-read the full narrative.
            - EFFICIENCY: prefer the shortest execution path that achieves the goal. If a capability \
              description states it already returns or saves the needed data, do NOT add another capability \
              solely to re-read or re-save that same data.
            - Output ONLY a valid JSON object — no markdown fences, no extra text.

            Output format:
            {
              "narrative": "<natural language instructions for the executor>",
              "selectedCapabilityIds": ["<id1>", "<id2>"],
              "capabilityInputDescriptions": {
                "<id1>": "<what to pass as input to this capability>",
                "<id2>": "<what to pass; may reference the output of id1>"
              },
              "reasoning": "<why you chose these capabilities>"
            }
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Object process(Object input) {
        IContextBus bus = getContextBus();
        TaskExecutionState state = bus.getTransmit(ContextBusKeys.STATE);
        ChainActor chainActor = bus.getTransmit(ContextBusKeys.CHAIN_ACTOR);
        ModelProvider llmProvider = bus.getTransmit(ContextBusKeys.LLM_PROVIDER);
        ModelSpec modelSpec = bus.getTransmit(ContextBusKeys.DEFAULT_MODEL);
        AgentEventListener listener = bus.getTransmit(ContextBusKeys.EVENT_LISTENER);
        List<CapabilityCandidate> candidates = bus.getTransmit(ContextBusKeys.CANDIDATES);
        String sessionSummary = bus.getTransmit(ContextBusKeys.SESSION_SUMMARY);
        TaskStore taskStore = bus.getTransmit(ContextBusKeys.TASK_STORE);

        BaseChatModel llm = llmProvider.provide(modelSpec);
        String taskId = state.getTaskId();
        int round = state.getCurrentRound();
        FlowInstance flow = buildFlow(chainActor, llm,
                text -> listener.onEvent(AgentEvent.of(taskId, round, EventType.PLAN_LLM_RESPONDED, text)));

        String userPrompt = buildPrompt(state, candidates, sessionSummary);
        ChatGeneration result = chainActor.invoke(flow, Map.of("prompt", userPrompt));

        PlanOutput plan = parsePlan(result.getText());

        bus.putTransmit(ContextBusKeys.PLAN_NARRATIVE, plan.getNarrative());
        bus.putTransmit(ContextBusKeys.SELECTED_CAPS, plan.getSelectedCapabilityIds());
        bus.putTransmit(ContextBusKeys.CAPABILITY_INPUT_DESCS, plan.getCapabilityInputDescriptions());

        currentRound(state).setPlan(plan);
        state.setUpdatedAt(System.currentTimeMillis());

        listener.onEvent(AgentEvent.of(state.getTaskId(), state.getCurrentRound(), EventType.PLAN_COMPLETED,
                "Selected: " + plan.getSelectedCapabilityIds() + " | " + plan.getNarrative()));
        log.debug("Round {}: plan produced, selected caps: {}",
                state.getCurrentRound(), plan.getSelectedCapabilityIds());

        if (taskStore != null) taskStore.save(state);
        return null;
    }

    private FlowInstance buildFlow(ChainActor chainActor, BaseChatModel llm, Consumer<String> onLlm) {
        return chainActor.builder()
                .next(ChatPromptTemplate.fromMessages(List.of(
                        Pair.of("system", SYSTEM_PROMPT),
                        Pair.of("human", "${prompt}")
                )))
                .next(input -> {
                    if (onLlm != null) onLlm.accept(input.toString());
                    return input;
                })
                .next(llm)
                .next(new StrOutputParser())
                .build();
    }

    private String buildPrompt(TaskExecutionState state,
                               List<CapabilityCandidate> candidates,
                               String sessionSummary) {
        StringBuilder sb = new StringBuilder();

        if (sessionSummary != null && !sessionSummary.isBlank()) {
            sb.append("== Session history ==\n").append(sessionSummary).append("\n\n");
        }

        sb.append("Goal: ").append(state.getRequest().getGoal()).append("\n\n");

        String supplement = state.getRequest().getSupplementInput();
        if (supplement != null && !supplement.isBlank()) {
            sb.append("== User supplement ==\n").append(supplement).append("\n\n");
        }

        sb.append("Available capabilities:\n");
        if (candidates != null) {
            candidates.forEach(c -> sb.append("- ").append(c.getCapabilityId())
                    .append(" (").append(c.getName()).append("): ")
                    .append(c.getDescription()).append("\n"));
        }

        ReflectionHint lastHint = lastHint(state);
        if (lastHint != null) {
            sb.append("\nGuidance from previous round:\n");
            if (lastHint.getPlanAdjustment() != null) {
                sb.append("- Adjustment: ").append(lastHint.getPlanAdjustment()).append("\n");
            }
            if (lastHint.getAvoidCapabilityIds() != null && !lastHint.getAvoidCapabilityIds().isEmpty()) {
                sb.append("- Avoid: ").append(String.join(", ", lastHint.getAvoidCapabilityIds())).append("\n");
            }
            if (lastHint.getReason() != null) {
                sb.append("- Reason: ").append(lastHint.getReason()).append("\n");
            }
        }

        List<RoundRecord> rounds = state.getRounds();
        if (rounds.size() > 1) {
            sb.append("\nPrevious round summary:\n");
            RoundRecord prev = rounds.get(rounds.size() - 2);
            if (prev.getExecutionResult() != null && prev.getExecutionResult().getFinalText() != null) {
                String text = prev.getExecutionResult().getFinalText();
                sb.append(text.length() > 500 ? text.substring(0, 500) + "..." : text).append("\n");
            }
        }

        return sb.toString();
    }

    private PlanOutput parsePlan(String text) {
        String json = extractJson(text);
        try {
            return MAPPER.readValue(json, PlanOutput.class);
        } catch (Exception first) {
            try {
                return MAPPER.readValue(repairJson(json), PlanOutput.class);
            } catch (Exception e) {
                log.warn("Failed to parse PlanOutput, using raw text as narrative: {}", first.getMessage());
                PlanOutput fallback = new PlanOutput();
                fallback.setNarrative(text);
                fallback.setSelectedCapabilityIds(List.of());
                fallback.setReasoning("parse error");
                return fallback;
            }
        }
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /**
     * Escapes unescaped double-quotes inside JSON string values.
     * Heuristic: a '"' that is NOT followed (after optional whitespace) by a JSON
     * structural character (':', ',', '}', ']') while we are inside a string is an
     * unescaped quote that the LLM forgot to escape.
     */
    private String repairJson(String json) {
        StringBuilder sb = new StringBuilder(json.length() + 16);
        boolean inString = false;
        boolean escaped  = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                sb.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                if (!inString) {
                    inString = true;
                    sb.append(c);
                } else {
                    // Look ahead past whitespace to see if this is a valid terminator
                    int j = i + 1;
                    while (j < json.length() && json.charAt(j) == ' ') j++;
                    char next = j < json.length() ? json.charAt(j) : 0;
                    if (next == ':' || next == ',' || next == '}' || next == ']' || next == '\n' || next == '\r' || next == 0) {
                        inString = false;
                        sb.append(c);
                    } else {
                        sb.append("\\\"");   // escape the stray quote
                    }
                }
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private ReflectionHint lastHint(TaskExecutionState state) {
        List<RoundRecord> rounds = state.getRounds();
        if (rounds == null || rounds.size() < 2) return null;
        for (int i = rounds.size() - 2; i >= 0; i--) {
            RoundRecord r = rounds.get(i);
            if (r.getReflection() != null && r.getReflection().getHintForNext() != null) {
                return r.getReflection().getHintForNext();
            }
        }
        return null;
    }

    private RoundRecord currentRound(TaskExecutionState state) {
        List<RoundRecord> rounds = state.getRounds();
        return rounds.get(rounds.size() - 1);
    }

    @Override
    public void stop() {
        // LLM call is synchronous; stop signal is checked between rounds
    }
}
