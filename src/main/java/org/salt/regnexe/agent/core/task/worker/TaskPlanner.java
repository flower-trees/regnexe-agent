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
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.MessageType;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.value.ChatPromptValue;

import java.util.ArrayList;
import java.util.List;
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
            - DATA MATERIALIZATION: If the input to a capability requires data that already appears in the \
              Goal or session history (e.g. existing topics, user modification feedback, chapter content), \
              you MUST copy that data verbatim into capabilityInputDescriptions. Never use references such \
              as "see above", "as described", or "from the goal" — the executor has no access to those references.
            - EFFICIENCY: prefer the shortest execution path that achieves the goal. If a capability \
              description states it already returns or saves the needed data, do NOT add another capability \
              solely to re-read or re-save that same data.
            - UNIQUE IDs: selectedCapabilityIds must contain each capability ID at most once. \
              If the goal involves conditional retries (e.g. "if QC fails, retry"), list the capability \
              once — the executor agent will call it again as needed based on the narrative.
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
        List<HistoryInfos> sessionHistory = bus.getTransmit(ContextBusKeys.SESSION_HISTORY);
        TaskStore taskStore = bus.getTransmit(ContextBusKeys.TASK_STORE);

        BaseChatModel llm = llmProvider.provide(modelSpec);
        String taskId = state.getTaskId();
        int round = state.getCurrentRound();
        FlowInstance flow = buildFlow(chainActor, llm,
                text -> listener.dispatch(AgentEvent.of(taskId, round, EventType.PLAN_LLM_RESPONDED, text)));

        String candidateNames = candidates == null ? "" : candidates.stream()
                .map(c -> c.getName()).collect(java.util.stream.Collectors.joining(", "));
        listener.dispatch(AgentEvent.of(taskId, round, EventType.PLAN_STARTED,
                "Goal: " + state.getRequest().getGoal() + " | Candidates: " + candidateNames));

        ChatPromptValue prompt = buildChatPrompt(state, candidates, sessionHistory);
        ChatGeneration result = chainActor.invoke(flow, prompt);

        PlanOutput plan = parsePlan(result.getText());

        bus.putTransmit(ContextBusKeys.PLAN_NARRATIVE, plan.getNarrative());
        bus.putTransmit(ContextBusKeys.SELECTED_CAPS, plan.getSelectedCapabilityIds());
        bus.putTransmit(ContextBusKeys.CAPABILITY_INPUT_DESCS, plan.getCapabilityInputDescriptions());

        currentRound(state).setPlan(plan);
        state.setUpdatedAt(System.currentTimeMillis());

        listener.dispatch(AgentEvent.of(state.getTaskId(), state.getCurrentRound(), EventType.PLAN_COMPLETED,
                "Selected: " + plan.getSelectedCapabilityIds() + " | " + plan.getNarrative()));
        log.debug("Round {}: plan produced, selected caps: {}",
                state.getCurrentRound(), plan.getSelectedCapabilityIds());

        if (taskStore != null) taskStore.save(state);
        return null;
    }

    private FlowInstance buildFlow(ChainActor chainActor, BaseChatModel llm, Consumer<String> onLlm) {
        return chainActor.builder()
                .next(input -> {
                    if (onLlm != null) onLlm.accept(input.toString());
                    return input;
                })
                .next(llm)
                .next(new StrOutputParser())
                .build();
    }

    private ChatPromptValue buildChatPrompt(TaskExecutionState state,
                                            List<CapabilityCandidate> candidates,
                                            List<HistoryInfos> sessionHistory) {
        List<BaseMessage> messages = new ArrayList<>();

        // ── System message: SYSTEM_PROMPT + SUMMARY + capabilities ───────────
        StringBuilder systemSb = new StringBuilder(SYSTEM_PROMPT);

        if (sessionHistory != null) {
            for (HistoryInfos h : sessionHistory) {
                if (h.getType() == HistoryInfos.Type.SUMMARY) {
                    for (BaseMessage msg : h.getMessages()) {
                        systemSb.append("\n\n").append(msg.getContent());
                    }
                }
            }
        }

        systemSb.append("\n\nAvailable capabilities:\n");
        if (candidates != null) {
            candidates.forEach(c -> systemSb.append("- ").append(c.getCapabilityId())
                    .append(" (").append(c.getName()).append("): ")
                    .append(c.getDescription()).append("\n"));
        }

        messages.add(BaseMessage.fromMessage(MessageType.SYSTEM.getCode(), systemSb.toString()));

        // ── NORMAL history as actual Human/AI message pairs ───────────────────
        boolean hasHistory = false;
        if (sessionHistory != null) {
            for (HistoryInfos h : sessionHistory) {
                if (h.getType() != HistoryInfos.Type.NORMAL) continue;
                for (BaseMessage msg : h.getMessages()) {
                    messages.add(BaseMessage.fromMessage(msg.getRole(), msg.getContent()));
                }
                hasHistory = true;
            }
        }

        // ── Separator: break few-shot mimicking before the current goal ───────
        if (hasHistory) {
            messages.add(BaseMessage.fromMessage(MessageType.SYSTEM.getCode(),
                    "The conversation history above is provided for context only. " +
                    "Now output a JSON execution plan for the new goal below. " +
                    "Do NOT replicate any prior response format — output ONLY the JSON object."));
        }

        // ── Current goal HumanMessage ─────────────────────────────────────────
        StringBuilder humanSb = new StringBuilder();
        humanSb.append("Goal: ").append(state.getRequest().getGoal());

        String supplement = state.getRequest().getSupplementInput();
        if (supplement != null && !supplement.isBlank()) {
            humanSb.append("\n\n== User supplement ==\n").append(supplement);
        }

        ReflectionHint lastHint = lastHint(state);
        if (lastHint != null) {
            humanSb.append("\n\nGuidance from previous round:");
            if (lastHint.getPlanAdjustment() != null) {
                humanSb.append("\n- Adjustment: ").append(lastHint.getPlanAdjustment());
            }
            if (lastHint.getAvoidCapabilityIds() != null && !lastHint.getAvoidCapabilityIds().isEmpty()) {
                humanSb.append("\n- Avoid: ").append(String.join(", ", lastHint.getAvoidCapabilityIds()));
            }
            if (lastHint.getReason() != null) {
                humanSb.append("\n- Reason: ").append(lastHint.getReason());
            }
        }

        List<RoundRecord> rounds = state.getRounds();
        if (rounds.size() > 1) {
            RoundRecord prev = rounds.get(rounds.size() - 2);
            if (prev.getExecutionResult() != null && prev.getExecutionResult().getFinalText() != null) {
                humanSb.append("\n\nPrevious round summary:\n").append(prev.getExecutionResult().getFinalText());
            }
        }

        messages.add(BaseMessage.fromMessage(MessageType.HUMAN.getCode(), humanSb.toString()));

        return ChatPromptValue.builder().messages(messages).build();
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
