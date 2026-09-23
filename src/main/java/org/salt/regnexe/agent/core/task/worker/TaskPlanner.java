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
import org.salt.regnexe.agent.core.common.enums.ExecutionStatus;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.common.util.RoundRecords;
import org.salt.regnexe.agent.core.event.AgentEvent;
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.agent.core.llm.ModelProvider;
import org.salt.regnexe.agent.core.llm.ModelSpec;
import org.salt.regnexe.agent.core.task.state.RoundRecord;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.capability.CapabilityCandidate;
import org.salt.regnexe.agent.core.task.state.execution.ExecutionOutput;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
            - NARRATIVE LENGTH: keep narrative short — a few sentences covering sequence/strategy only \
              (what to do, and in what order). Do NOT restate capability input data (that belongs in \
              capabilityInputDescriptions) or the final-answer checklist (that belongs in \
              finalAnswerRequirements).
            - CRITICAL: every capability name you mention in the narrative MUST also appear in selectedCapabilityIds.
            - TOOL DEPENDENCIES: If a selected SKILL or SUB_AGENT lists allowedTools, you MUST also include each \
              allowed tool id in selectedCapabilityIds. These tools are inherited dependencies for that capability; \
              include them so the executor can make them available, but do not present them as separate top-level \
              user tasks unless the user explicitly asked to call them directly.
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
            - FINAL ANSWER REQUIREMENTS: list the concrete user-facing items the executor's final answer \
              must include — every deliverable the round is expected to produce.
            - ITERATIONS HINT: Estimate how many executor iterations this plan will need and set \
              iterationsHint accordingly. Use these per-operation costs as a guide:
              * Each file read (read_file, list_files, search_files): ~2 iterations
              * Each file write or edit (write_file, edit_file, with confirmation): ~3 iterations
              * Each bash execution (with possible confirmation): ~2 iterations
              * Each sub-agent or skill call: ~5 iterations
              Sum the costs for all planned operations, then add a 25%% buffer. \
              If the total is <= the default (%s), omit the field or set it to null — but if this \
              round's plan is simpler than that default suggests, set iterationsHint to your own \
              lower estimate anyway: it also tightens the budget, not just raises it, so a small \
              plan gets a small ceiling instead of the full default room to keep going after the \
              goal is already met.
            - Output ONLY a valid JSON object — no markdown fences, no extra text.

            Output format:
            {
              "narrative": "<natural language instructions for the executor>",
              "selectedCapabilityIds": ["<id1>", "<id2>"],
              "capabilityInputDescriptions": {
                "<id1>": "<what to pass as input to this capability>",
                "<id2>": "<what to pass; may reference the output of id1>"
              },
              "finalAnswerRequirements": ["<required item 1>", "<required item 2>"],
              "iterationsHint": <integer or null>
            }
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_PLAN_RETRIES = 2;
    private static final int MAX_SAFE_ITERATIONS = 200;
    private static final String PARSE_ERROR_CORRECTION =
            "[PARSE ERROR] Your previous response was not valid JSON. " +
            "Do NOT output XML, markdown, tool-call syntax, or any other format. " +
            "Output ONLY a plain JSON object matching the required schema. No ```json fences.";

    @Override
    public Object process(Object input) {
        IContextBus bus = getContextBus();
        TaskExecutionState state = bus.getTransmit(ContextBusKeys.STATE);
        if (state.getStatus() != TaskStatus.RUNNING) {
            log.debug("TaskPlanner skipped because task status is {}", state.getStatus());
            return null;
        }
        ChainActor chainActor = bus.getTransmit(ContextBusKeys.CHAIN_ACTOR);
        ModelProvider llmProvider = bus.getTransmit(ContextBusKeys.LLM_PROVIDER);
        // Falls back to DEFAULT_MODEL when no Planner-specific override is configured —
        // see ContextBusKeys.PLANNER_MODEL's javadoc for why a stronger model here specifically
        // (vs. Execute) is a reasonable place to spend more.
        ModelSpec plannerModelSpec = bus.getTransmit(ContextBusKeys.PLANNER_MODEL);
        ModelSpec modelSpec = plannerModelSpec != null
                ? plannerModelSpec : bus.getTransmit(ContextBusKeys.DEFAULT_MODEL);
        AgentEventListener listener = bus.getTransmit(ContextBusKeys.EVENT_LISTENER);
        List<CapabilityCandidate> candidates = bus.getTransmit(ContextBusKeys.CANDIDATES);
        List<HistoryInfos> sessionHistory = bus.getTransmit(ContextBusKeys.SESSION_HISTORY);
        TaskStore taskStore = bus.getTransmit(ContextBusKeys.TASK_STORE);
        String projectMemory = bus.getTransmit(ContextBusKeys.PROJECT_MEMORY);
        // The untouched original config value — NOT MAX_AGENT_ITERATIONS itself, which earlier
        // rounds of this same task may already have overridden (see the iterationsHint block
        // below and MAX_AGENT_ITERATIONS_DEFAULT's javadoc). Falls back to whatever
        // MAX_AGENT_ITERATIONS currently holds for callers that never set the DEFAULT key
        // (e.g. an older embedding of regnexe-agent), then to a hardcoded 20 if neither is set.
        Integer defaultIterationsBoxed = bus.getTransmit(ContextBusKeys.MAX_AGENT_ITERATIONS_DEFAULT);
        if (defaultIterationsBoxed == null) {
            defaultIterationsBoxed = bus.getTransmit(ContextBusKeys.MAX_AGENT_ITERATIONS);
        }
        int defaultIterations = defaultIterationsBoxed != null ? defaultIterationsBoxed : 20;

        String taskId = state.getTaskId();
        int round = state.getCurrentRound();

        String candidateNames = candidates == null ? "" : candidates.stream()
                .map(c -> c.getName()).collect(java.util.stream.Collectors.joining(", "));
        listener.dispatch(AgentEvent.of(taskId, round, EventType.PLAN_STARTED,
                "Goal: " + state.getRequest().getGoal() + " | Candidates: " + candidateNames));

        boolean hasCandidates = candidates != null && !candidates.isEmpty();
        // Session history is normally only sent on round 1 — from round 2 on, this round's own
        // "Progress so far" carries the same signal more cheaply. But if round 1 crashes before
        // Execute ever runs, round 2 gets neither the history nor real task progress, and can lose
        // track of what a short follow-up goal (e.g. "continue") even refers to. So keep resending
        // history every round until the task has at least one round that actually completed.
        boolean isFirstRound = round == 1;
        boolean hasHistory = sessionHistory != null && !sessionHistory.isEmpty()
                && (isFirstRound || noRoundSucceededYet(state));

        PlanOutput plan;
        if (!hasCandidates) {
            // No capabilities — skip the LLM planning call entirely.
            // The executor will answer the goal directly via its own LLM call.
            plan = new PlanOutput();
            plan.setSelectedCapabilityIds(List.of());
            plan.setNarrative("No tools available. Provide a direct answer to the goal.");
            plan.setFinalAnswerRequirements(List.of());
            normalizePlan(plan);
        } else {
            // withJsonMode(): Plan output is parsed as structured JSON (PlanOutput), so ask the
            // vendor to constrain generation to valid JSON syntax where supported. This does NOT
            // affect Execute — CapabilityExecutor provides its own llm instance for tool-calling.
            BaseChatModel llm = llmProvider.provide(modelSpec).withJsonMode();
            FlowInstance flow = buildFlow(chainActor, llm,
                    text -> listener.dispatch(AgentEvent.of(taskId, round, EventType.PLAN_LLM_RESPONDED, text)));

            ChatPromptValue basePrompt = buildChatPrompt(state, candidates, hasHistory ? sessionHistory : null, projectMemory, defaultIterations);
            List<BaseMessage> messages = new ArrayList<>(basePrompt.getMessages());
            plan = null;
            String lastRaw = null;
            for (int attempt = 0; attempt <= MAX_PLAN_RETRIES; attempt++) {
                // A call failure (network/HTTP) is a different failure mode from a parse failure
                // below, but reuses the same retry budget — worth one more attempt before falling
                // through to recoverPlan()'s deterministic, LLM-free fallback.
                String gen;
                try {
                    ChatGeneration callResult = chainActor.invoke(flow,
                            ChatPromptValue.builder().messages(messages).build());
                    gen = callResult.getText();
                } catch (Exception e) {
                    log.warn("Round {}: plan call failed on attempt {}/{}: {}",
                            round, attempt + 1, MAX_PLAN_RETRIES + 1, e.getMessage());
                    continue;
                }
                lastRaw = gen;
                plan = tryParsePlan(lastRaw);
                if (plan != null) break;
                if (attempt < MAX_PLAN_RETRIES) {
                    log.warn("Round {}: plan parse failed on attempt {}/{}, retrying",
                            round, attempt + 1, MAX_PLAN_RETRIES);
                    messages = new ArrayList<>(messages);
                    messages.add(BaseMessage.fromMessage(MessageType.AI.getCode(), lastRaw));
                    messages.add(BaseMessage.fromMessage(MessageType.SYSTEM.getCode(), PARSE_ERROR_CORRECTION));
                }
            }
            if (plan == null) {
                log.warn("Round {}: plan parse failed after {} retries, using recovery plan", round, MAX_PLAN_RETRIES);
                plan = recoverPlan(state, candidates, lastRaw);
            }
            normalizePlan(plan);
            expandSelectedAllowedTools(plan, candidates);
        }

        // Append session history to narrative so the executor LLM sees conversation
        // context directly, regardless of whether the planner LLM was called.
        if (hasHistory) {
            plan.setNarrative(plan.getNarrative() + formatHistoryForNarrative(sessionHistory));
        }

        bus.putTransmit(ContextBusKeys.PLAN_NARRATIVE, plan.getNarrative());
        bus.putTransmit(ContextBusKeys.SELECTED_CAPS, plan.getSelectedCapabilityIds());
        bus.putTransmit(ContextBusKeys.CAPABILITY_INPUT_DESCS, plan.getCapabilityInputDescriptions());

        // Set this round's maxAgentIterations from the plan's own estimate, in either direction —
        // not just upward. A hint below the default should tighten the ceiling too, otherwise a
        // simple round keeps the full default room even after its goal is met and the executor
        // wanders into unnecessary extra tool calls. Always computed relative to defaultIterations
        // (the untouched config value), not whatever MAX_AGENT_ITERATIONS currently holds, since
        // that may already carry a previous round's override. A flat 30% margin absorbs retries
        // and extra confirmations the estimate didn't account for.
        if (plan.getIterationsHint() != null && plan.getIterationsHint() > 0) {
            int hint = Math.min(plan.getIterationsHint(), MAX_SAFE_ITERATIONS);
            int hintWithMargin = (int) Math.ceil(hint * 1.3);
            Integer current = bus.getTransmit(ContextBusKeys.MAX_AGENT_ITERATIONS);
            if (current == null || hintWithMargin != current) {
                bus.putTransmit(ContextBusKeys.MAX_AGENT_ITERATIONS, hintWithMargin);
                log.debug("Round {}: iterationsHint={} (+30% margin={}) applied (was {}, default {})",
                        round, hint, hintWithMargin, current, defaultIterations);
            }
        } else {
            // No hint this round (Planner judged the default sufficient, or this is the
            // no-candidates direct-answer branch) — reset to the real baseline instead of
            // silently keeping whatever a previous round's override left behind.
            bus.putTransmit(ContextBusKeys.MAX_AGENT_ITERATIONS, defaultIterations);
        }

        RoundRecords.current(state).setPlan(plan);
        state.setUpdatedAt(System.currentTimeMillis());

        listener.dispatch(AgentEvent.of(state.getTaskId(), state.getCurrentRound(), EventType.PLAN_COMPLETED,
                "Selected: " + plan.getSelectedCapabilityIds()
                + " | " + plan.getNarrative()));
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
                                            List<HistoryInfos> sessionHistory,
                                            String projectMemory,
                                            int defaultIterations) {
        List<BaseMessage> messages = new ArrayList<>();

        // ── System message: SYSTEM_PROMPT + PROJECT_MEMORY + SUMMARY + capabilities ──
        // .formatted() resolves both the real %s (the actual configured default, not a stale
        // hardcoded number) and the escaped %% ("25%% buffer" -> "25% buffer" in what the LLM
        // actually reads).
        StringBuilder systemSb = new StringBuilder(SYSTEM_PROMPT.formatted(defaultIterations));

        // Long-term project memory (REX.md) — always present once configured, independent of
        // sessionId/history.
        if (projectMemory != null && !projectMemory.isBlank()) {
            systemSb.append("\n\n---\n\nProject memory:\n").append(projectMemory);
        }

        // Labeled explicitly here rather than trusting the content to already say what it is —
        // PeriodicConversationSummaryMemoryStorer happens to prefix its own summaries with
        // "Conversation summary: ", but that's a convention of one ConversationMemory
        // implementation, not a guarantee of HistoryInfos.Type.SUMMARY itself.
        if (sessionHistory != null) {
            for (HistoryInfos h : sessionHistory) {
                if (h.getType() == HistoryInfos.Type.SUMMARY) {
                    for (BaseMessage msg : h.getMessages()) {
                        systemSb.append("\n\nSession summary:\n").append(msg.getContent());
                    }
                }
            }
        }

        systemSb.append("\n\nAvailable capabilities:\n");
        if (candidates != null) {
            candidates.forEach(c -> systemSb.append("- ").append(c.getCapabilityId())
                    .append(" (").append(c.getName()).append("): ")
                    .append(c.getDescription())
                    .append(formatAllowedTools(c))
                    .append("\n"));
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

        // Recent-round progress: every completed round's own finalText, unbounded — it's already
        // Execute's own synthesized answer (short, human-readable), not raw tool-call data, so
        // keeping the full history costs little. priorStepsSummary (Execute's own tool-step
        // compaction) is deliberately not read here: it's tuned for Execute's own continuity, not
        // Planner's decision-making, and is only ever populated when a SlidingWindowContext is
        // configured and has actually triggered — an unreliable, wrong-audience signal now that
        // finalText coverage is unbounded.
        StringBuilder progressSb = new StringBuilder();
        List<RoundRecord> rounds = state.getRounds();
        for (int i = 0; i < rounds.size() - 1; i++) {
            RoundRecord r = rounds.get(i);
            String summary = r.getExecutionResult() != null ? r.getExecutionResult().getFinalText() : null;
            if (summary != null && !summary.isBlank()) {
                progressSb.append("\n\nRound ").append(r.getRoundNumber()).append(" summary:\n").append(summary);
            }
        }
        if (progressSb.length() > 0) {
            humanSb.append("\n\nProgress so far:").append(progressSb);
        }

        messages.add(BaseMessage.fromMessage(MessageType.HUMAN.getCode(), humanSb.toString()));

        return ChatPromptValue.builder().messages(messages).build();
    }

    private PlanOutput tryParsePlan(String text) {
        String json = extractJson(text);
        try {
            return MAPPER.readValue(json, PlanOutput.class);
        } catch (Exception first) {
            try {
                return MAPPER.readValue(repairJson(json), PlanOutput.class);
            } catch (Exception e) {
                log.debug("Plan parse attempt failed: {}", first.getMessage());
                return null;
            }
        }
    }

    private PlanOutput recoverPlan(TaskExecutionState state, List<CapabilityCandidate> candidates, String rawText) {
        // Level 1: reuse last successful plan from a previous round
        List<RoundRecord> rounds = state.getRounds();
        if (rounds.size() >= 2) {
            for (int i = rounds.size() - 2; i >= 0; i--) {
                PlanOutput prev = rounds.get(i).getPlan();
                if (prev != null && prev.getSelectedCapabilityIds() != null
                        && !prev.getSelectedCapabilityIds().isEmpty()) {
                    PlanOutput recovery = new PlanOutput();
                    recovery.setNarrative("[Recovery: reusing plan from round " + (i + 1) + "]\n" + prev.getNarrative());
                    recovery.setSelectedCapabilityIds(new ArrayList<>(prev.getSelectedCapabilityIds()));
                    recovery.setCapabilityInputDescriptions(prev.getCapabilityInputDescriptions());
                    recovery.setFinalAnswerRequirements(prev.getFinalAnswerRequirements());
                    log.warn("Plan recovery: reusing round-{} plan", i + 1);
                    return recovery;
                }
            }
        }
        // Level 2: minimal default — select all candidates, direct synthesis
        PlanOutput minimal = new PlanOutput();
        minimal.setNarrative("Plan parse failed. Use all available tools to fulfill the goal: "
                + state.getRequest().getGoal());
        List<String> allIds = candidates == null ? List.of() :
                candidates.stream().map(CapabilityCandidate::getCapabilityId)
                        .collect(java.util.stream.Collectors.toList());
        minimal.setSelectedCapabilityIds(allIds);
        minimal.setFinalAnswerRequirements(List.of());
        return minimal;
    }

    private void normalizePlan(PlanOutput plan) {
        if (plan == null) return;
        if (plan.getSelectedCapabilityIds() == null) {
            plan.setSelectedCapabilityIds(List.of());
        }
        if (plan.getCapabilityInputDescriptions() == null) {
            plan.setCapabilityInputDescriptions(java.util.Collections.emptyMap());
        }
        if (plan.getFinalAnswerRequirements() == null) {
            plan.setFinalAnswerRequirements(List.of());
        }
    }

    private void expandSelectedAllowedTools(PlanOutput plan, List<CapabilityCandidate> candidates) {
        if (plan == null || plan.getSelectedCapabilityIds() == null || candidates == null) return;

        Set<String> selected = new LinkedHashSet<>(plan.getSelectedCapabilityIds());
        for (CapabilityCandidate candidate : candidates) {
            if (!selected.contains(candidate.getCapabilityId())) continue;
            if (candidate.getAllowedTools() == null || candidate.getAllowedTools().isEmpty()) continue;
            selected.addAll(candidate.getAllowedTools());
        }
        plan.setSelectedCapabilityIds(new ArrayList<>(selected));
    }

    private String formatAllowedTools(CapabilityCandidate candidate) {
        if (candidate.getAllowedTools() == null || candidate.getAllowedTools().isEmpty()) {
            return "";
        }
        return " | allowedTools: " + String.join(", ", candidate.getAllowedTools());
    }

    private String formatHistoryForNarrative(List<HistoryInfos> sessionHistory) {
        StringBuilder sb = new StringBuilder("\n\nConversation history:\n");
        for (HistoryInfos h : sessionHistory) {
            if (h.getType() == HistoryInfos.Type.SUMMARY) {
                for (BaseMessage msg : h.getMessages()) {
                    sb.append(msg.getContent()).append("\n");
                }
            } else {
                for (BaseMessage msg : h.getMessages()) {
                    String role = MessageType.HUMAN.equalsV(msg.getRole()) ? "Human" : "Assistant";
                    sb.append(role).append(": ").append(msg.getContent()).append("\n");
                }
            }
        }
        return sb.toString();
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

    /** True if no round before the current one ever completed Execute without crashing. */
    private boolean noRoundSucceededYet(TaskExecutionState state) {
        List<RoundRecord> rounds = state.getRounds();
        if (rounds == null) return true;
        for (RoundRecord r : rounds) {
            if (r.getRoundNumber() == state.getCurrentRound()) continue; // this round, not yet run
            ExecutionOutput exec = r.getExecutionResult();
            if (exec != null && exec.getStatus() == ExecutionStatus.SUCCESS) {
                return false;
            }
        }
        return true;
    }


    @Override
    public void stop() {
        // LLM call is synchronous; stop signal is checked between rounds
    }
}
