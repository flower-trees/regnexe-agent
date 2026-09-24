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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.salt.function.flow.FlowInstance;
import org.salt.function.flow.context.IContextBus;
import org.salt.function.flow.node.FlowNode;
import org.salt.regnexe.agent.core.common.enums.ReflectionAction;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.AgentEvent;
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.agent.core.llm.ModelProvider;
import org.salt.regnexe.agent.core.llm.ModelSpec;
import org.salt.regnexe.agent.core.common.util.RoundRecords;
import org.salt.regnexe.agent.core.task.state.RoundRecord;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.execution.ToolExecutionRecord;
import org.salt.regnexe.agent.core.task.state.plan.PlanOutput;
import org.salt.regnexe.agent.core.task.state.reflection.ReflectionDecision;
import org.salt.regnexe.agent.core.task.state.reflection.ReflectionHint;
import org.salt.regnexe.agent.core.task.store.TaskStore;
import org.salt.jlangchain.core.ChainActor;
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
 * Evaluates the execution result and decides whether to FINISH, CONTINUE, or ESCALATE.
 * Writes only to state (not to ContextBus); the next round's workers read
 * the hint from state.getRounds().
 */
@Slf4j
public class Reflector extends FlowNode<Object, Object> implements Worker {

    private static final String SYSTEM_PROMPT = """
            You are a reflection judge. Your job is to evaluate whether a task has been completed \
            successfully and decide the next action.

            Actions:
            - FINISH: The goal has been fully achieved. No further rounds needed.
            - CONTINUE: Progress was made but the goal is not yet complete. Provide hints for the next round.
            - ESCALATE: The task cannot be completed (unrecoverable error, impossible goal, etc.).

            Rules:
            - hintForNext must be null when action is FINISH or ESCALATE.
            - CROSS-CHECK: If the Goal specifies concrete constraints (specific items to upgrade, specific \
              settings or scenes, named entities), verify the execution result honors those constraints. \
              If the result deviates — e.g. the setting changes, named entities disappear, or required \
              items are replaced wholesale — action should be CONTINUE, not FINISH. Describe the deviation \
              in planAdjustment.
            - CROSS-CHECK AGAINST ACTUAL TOOL CALLS: The "Execution result" text is Execute's own \
              self-report, not verified fact — it can claim outcomes (files written, records inserted, \
              uploads completed) that the tool calls below never actually performed. Compare the claim \
              against "Tool calls this round" below: if the plan called for a specific action (e.g. \
              inserting a record, uploading a file) and no matching tool call actually appears, or far \
              fewer were made than the result claims (e.g. "wrote 4 articles" but only 1 write_file call \
              exists), treat this as NOT done — action should be CONTINUE (redo the missing part) or \
              ESCALATE (if clearly stuck), never FINISH on the strength of the claim alone.
            - Output ONLY a valid JSON object — no markdown fences, no extra text.

            Output format:
            {
              "action": "FINISH" | "CONTINUE" | "ESCALATE",
              "reason": "<why>",
              "hintForNext": null | {
                "requestResearch": false,
                "searchDirection": null,
                "excludeCapabilityIds": [],
                "planAdjustment": "<what to adjust>",
                "avoidCapabilityIds": [],
                "reason": "<hint reason>"
              }
            }
            """;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final int MAX_REFLECT_RETRIES = 2;
    private static final String PARSE_ERROR_CORRECTION =
            "[PARSE ERROR] Your previous response was not valid JSON. " +
            "Do NOT output prose, markdown, or any other format. " +
            "Output ONLY a plain JSON object matching the required schema. No ```json fences.";

    @Override
    public Object process(Object input) {
        IContextBus bus = getContextBus();
        TaskExecutionState state = bus.getTransmit(ContextBusKeys.STATE);
        if (state.getStatus() != TaskStatus.RUNNING) {
            log.debug("Reflector skipped because task status is {}", state.getStatus());
            return null;
        }
        ChainActor chainActor = bus.getTransmit(ContextBusKeys.CHAIN_ACTOR);
        ModelProvider llmProvider = bus.getTransmit(ContextBusKeys.LLM_PROVIDER);
        // Falls back to DEFAULT_MODEL when no Reflector-specific override is configured —
        // see ContextBusKeys.REFLECTOR_MODEL's javadoc for why judgment quality here specifically
        // (a wrong FINISH is a one-way door, unlike a Planner or Execute mistake) has outsized
        // leverage relative to its own small per-call cost.
        ModelSpec reflectorModelSpec = bus.getTransmit(ContextBusKeys.REFLECTOR_MODEL);
        ModelSpec modelSpec = reflectorModelSpec != null
                ? reflectorModelSpec : bus.getTransmit(ContextBusKeys.DEFAULT_MODEL);
        AgentEventListener listener = bus.getTransmit(ContextBusKeys.EVENT_LISTENER);
        TaskStore taskStore = bus.getTransmit(ContextBusKeys.TASK_STORE);

        String execText = bus.getTransmit(ContextBusKeys.EXEC_TEXT);
        if ((execText == null || execText.isBlank()) && state.getLastToolResult() != null) {
            execText = state.getLastToolResult();
        }

        // withJsonMode(): ReflectionDecision is parsed as structured JSON.
        BaseChatModel llm = llmProvider.provide(modelSpec).withJsonMode();
        String taskId = state.getTaskId();
        int roundNum = state.getCurrentRound();
        FlowInstance flow = buildFlow(chainActor, llm,
                text -> listener.dispatch(AgentEvent.of(taskId, roundNum, EventType.REFLECT_LLM_RESPONDED, text)));

        listener.dispatch(AgentEvent.of(taskId, roundNum, EventType.REFLECTION_STARTED,
                execText != null ? execText : "(no execution output)"));

        // Pre-LLM guard: catch structurally incomplete rounds before the LLM can hallucinate completion.
        RoundRecord roundRecord = RoundRecords.current(state);
        ReflectionDecision decision = evaluateGuardRules(roundRecord, state);
        if (decision != null) {
            log.warn("Round {}: guard rule forced {} — {}", roundNum, decision.getAction(), decision.getReason());
        } else {
            String userPrompt = buildPrompt(state, execText, roundRecord);
            List<BaseMessage> messages = new ArrayList<>(List.of(
                    BaseMessage.fromMessage(MessageType.SYSTEM.getCode(), SYSTEM_PROMPT),
                    BaseMessage.fromMessage(MessageType.HUMAN.getCode(), userPrompt)));
            // Same reasoning as TaskPlanner's plan-call retry, and same shared retry budget for
            // both failure modes: a transient call failure (proxy backend hiccup) has a real
            // chance of succeeding on the next attempt; a response that came back but wasn't
            // valid JSON (a model ignoring withJsonMode and replying in prose) also has a real
            // chance of self-correcting when told so directly, same as Planner's parse retry. If
            // every attempt is exhausted, fall back to an ESCALATE decision — same shape as
            // parseDecision()'s own parse-error fallback, just covering both "never got a
            // response" and "never got parseable JSON".
            String lastRaw = null;
            Exception lastError = null;
            for (int attempt = 0; attempt <= MAX_REFLECT_RETRIES; attempt++) {
                String raw;
                try {
                    ChatGeneration callResult = chainActor.invoke(flow,
                            ChatPromptValue.builder().messages(messages).build());
                    raw = callResult.getText();
                    lastError = null;
                } catch (Exception e) {
                    lastError = e;
                    log.warn("Round {}: reflect call failed on attempt {}/{}: {}",
                            roundNum, attempt + 1, MAX_REFLECT_RETRIES + 1, e.getMessage());
                    continue;
                }
                lastRaw = raw;
                decision = tryParseDecision(raw);
                if (decision != null) break;
                if (attempt < MAX_REFLECT_RETRIES) {
                    log.warn("Round {}: reflect parse failed on attempt {}/{}, retrying",
                            roundNum, attempt + 1, MAX_REFLECT_RETRIES);
                    messages = new ArrayList<>(messages);
                    messages.add(BaseMessage.fromMessage(MessageType.AI.getCode(), raw));
                    messages.add(BaseMessage.fromMessage(MessageType.SYSTEM.getCode(), PARSE_ERROR_CORRECTION));
                }
            }
            if (decision == null) {
                if (lastError != null) {
                    decision = new ReflectionDecision();
                    decision.setAction(ReflectionAction.ESCALATE);
                    decision.setReason("Reflect call failed after retries: " + lastError.getMessage());
                } else {
                    decision = parseDecision(lastRaw);
                }
            }
        }

        roundRecord.setReflection(decision);
        roundRecord.setEndedAt(System.currentTimeMillis());

        switch (decision.getAction()) {
            case FINISH   -> state.setStatus(TaskStatus.FINISHED);
            case ESCALATE -> state.setStatus(TaskStatus.ESCALATED);
            case CONTINUE -> {} // remain RUNNING
        }
        state.setUpdatedAt(System.currentTimeMillis());

        listener.dispatch(AgentEvent.of(state.getTaskId(), state.getCurrentRound(),
                EventType.REFLECTION_COMPLETED,
                decision.getAction() + " — " + decision.getReason()));
        log.debug("Round {}: reflection = {}, reason = {}",
                state.getCurrentRound(), decision.getAction(), decision.getReason());

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

    private String buildPrompt(TaskExecutionState state, String execText, RoundRecord round) {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(state.getRequest().getGoal()).append("\n\n");

        // What was planned for this round — needed so Reflector can tell "did the plan call for a
        // write/upload that never actually happened" rather than just "did anything happen at all".
        if (round.getPlan() != null && round.getPlan().getNarrative() != null) {
            sb.append("Plan:\n").append(round.getPlan().getNarrative()).append("\n\n");
        }

        // Inject factual tool execution count so the LLM cannot hallucinate completion from zero executions.
        List<ToolExecutionRecord> thisRoundCalls = toolExecutionsForRound(round);
        sb.append("Tools executed this round: ").append(thisRoundCalls.size()).append("\n\n");

        sb.append("Execution result:\n");
        sb.append(execText != null ? execText : "(no output)").append("\n\n");

        // Full tool-call list for THIS round — see the SYSTEM_PROMPT's cross-check rule. Real case
        // that motivated adding this: Execute claimed "4 articles written and uploaded" in execText
        // with 31 tool calls actually made, but only 1 write_file and zero db-insert calls among
        // them — Reflector, judging execText alone, had no way to catch the fabrication and marked
        // the task FINISHED. Cheap to include: entries are already bounded at the source
        // (ToolOutputOverflow), and scoped to just this round (ExecutionOutput.toolExecutions).
        if (!thisRoundCalls.isEmpty()) {
            sb.append("Tool calls this round (full list, in order):\n")
              .append(renderToolCalls(thisRoundCalls)).append("\n");
        }

        List<RoundRecord> rounds = state.getRounds();
        if (rounds.size() > 1) {
            sb.append("This is round ").append(state.getCurrentRound())
              .append(" of max ").append(state.getMaxRounds()).append(".\n");
        }

        return sb.toString();
    }

    /**
     * Hard-coded guard rules evaluated before the LLM is called.
     * Returns a forced decision if a structural violation is detected; null means proceed normally.
     *
     * Rule: capabilities were selected, zero tools ran this round, AND the task has no real
     * tool-call evidence anywhere yet (state.priorSteps empty) → cannot be FINISH. Scoped to "no
     * evidence anywhere", not just "none this round": Execute now carries real tool-call history
     * across rounds via the shared AgentTaskContext (state.priorSteps), so a later round correctly
     * answering from an earlier round's already-gathered data without making any new calls is
     * expected, not a sign anything went wrong — only flag it when nothing was ever actually run.
     */
    private ReflectionDecision evaluateGuardRules(RoundRecord round, TaskExecutionState state) {
        PlanOutput plan = round.getPlan();

        boolean capsSelected = plan != null
                && plan.getSelectedCapabilityIds() != null
                && !plan.getSelectedCapabilityIds().isEmpty();
        boolean noToolsRan = toolExecutionsForRound(round).isEmpty();
        boolean hasPriorEvidence = state.getPriorSteps() != null && !state.getPriorSteps().isEmpty();

        if (capsSelected && noToolsRan && !hasPriorEvidence) {
            int capsCount = plan.getSelectedCapabilityIds().size();
            ReflectionDecision decision = new ReflectionDecision();
            decision.setAction(ReflectionAction.CONTINUE);
            decision.setReason("Guard: " + capsCount + " capabilities selected but no tools executed this round");
            ReflectionHint hint = new ReflectionHint();
            hint.setPlanAdjustment(
                    "No tools ran despite " + capsCount + " capabilities being selected. "
                    + "Retry the plan — if tool confirmations were cancelled, accept them.");
            hint.setReason("zero tool executions");
            decision.setHintForNext(hint);
            return decision;
        }
        return null;
    }

    /** This round's own tool calls (see {@code ExecutionOutput#getToolExecutions()}), or empty if none ran. */
    private List<ToolExecutionRecord> toolExecutionsForRound(RoundRecord round) {
        if (round.getExecutionResult() == null || round.getExecutionResult().getToolExecutions() == null) {
            return List.of();
        }
        return round.getExecutionResult().getToolExecutions();
    }

    /** Returns null (instead of an ESCALATE fallback) on parse failure, so the retry loop can tell. */
    private ReflectionDecision tryParseDecision(String text) {
        try {
            String json = extractJson(text);
            ReflectionDecision decision = MAPPER.readValue(json, ReflectionDecision.class);
            return decision.getAction() != null ? decision : null;
        } catch (Exception e) {
            return null;
        }
    }

    private ReflectionDecision parseDecision(String text) {
        try {
            String json = extractJson(text);
            ReflectionDecision decision = MAPPER.readValue(json, ReflectionDecision.class);
            if (decision.getAction() == null) {
                decision.setAction(ReflectionAction.ESCALATE);
            }
            return decision;
        } catch (Exception e) {
            log.warn("Failed to parse ReflectionDecision, defaulting to ESCALATE: {}", e.getMessage());
            ReflectionDecision fallback = new ReflectionDecision();
            fallback.setAction(ReflectionAction.ESCALATE);
            fallback.setReason("parse error: " + e.getMessage());
            return fallback;
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

    /** Renders a round's tool calls as "- toolName args -> observation" lines, for the cross-check prompt. */
    private String renderToolCalls(List<ToolExecutionRecord> records) {
        StringBuilder sb = new StringBuilder();
        for (ToolExecutionRecord r : records) {
            sb.append("- ").append(r.getToolName());
            if (r.getArguments() != null && !r.getArguments().isBlank()) {
                sb.append(" ").append(r.getArguments());
            }
            sb.append(" -> ").append(r.getObservation()).append("\n");
        }
        return sb.toString();
    }

    @Override
    public void stop() {
        // LLM call is synchronous; stop signal is checked between rounds
    }
}
