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
import org.apache.commons.lang3.tuple.Pair;
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
import org.salt.regnexe.agent.core.task.state.RoundRecord;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.execution.ExecutionOutput;
import org.salt.regnexe.agent.core.task.state.plan.PlanOutput;
import org.salt.regnexe.agent.core.task.state.reflection.ReflectionDecision;
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
            - roundSummary is a hand-off note for the NEXT round's planner, not a user-facing answer. \
              Base it on the Execution result below. It should \
              let the next round skip redoing finished work and go straight to fixing what's broken. \
              Cover three things concisely: (1) concrete artifacts already produced this round — files \
              written, records committed, research already gathered, with enough specificity (ids, \
              filenames, search topics already covered) that the next round recognizes it doesn't need \
              to redo them; (2) if something failed, the SPECIFIC cause (e.g. the exact error type/line, \
              not just "an error occurred") — this is what lets the next round fix the one broken thing \
              instead of restarting everything; (3) what concretely remains. Always non-null, even on \
              FINISH/ESCALATE (a short "what was accomplished" note still has value there).
            - Output ONLY a valid JSON object — no markdown fences, no extra text.

            Output format:
            {
              "action": "FINISH" | "CONTINUE" | "ESCALATE",
              "reason": "<why>",
              "roundSummary": "<hand-off note for the next round, see rule above>",
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
        RoundRecord roundRecord = currentRound(state);
        ReflectionDecision decision = evaluateGuardRules(roundRecord, state);
        if (decision != null) {
            log.warn("Round {}: guard rule forced {} — {}", roundNum, decision.getAction(), decision.getReason());
        } else {
            String userPrompt = buildPrompt(state, execText, roundRecord);
            ChatGeneration result = chainActor.invoke(flow, Map.of("prompt", userPrompt));
            decision = parseDecision(result.getText());
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

    private String buildPrompt(TaskExecutionState state, String execText, RoundRecord round) {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(state.getRequest().getGoal()).append("\n\n");

        // Deliberately NOT re-sending plan.narrative/capabilityInputDescriptions here: Reflector
        // judges completion from what actually happened (tool count + execution result), not from
        // what was planned, and those fields (capabilityInputDescriptions especially, which can
        // contain verbatim-materialized goal/session data) are already sent once to the Planner's
        // own output and once to CapabilityExecutor — a third copy here added cost with no signal.

        // Inject factual tool execution count so the LLM cannot hallucinate completion from zero executions.
        ExecutionOutput exec = round.getExecutionResult();
        int toolCount = (exec != null && exec.getToolExecutions() != null) ? exec.getToolExecutions().size() : 0;
        sb.append("Tools executed this round: ").append(toolCount).append("\n\n");

        sb.append("Execution result:\n");
        sb.append(execText != null ? execText : "(no output)").append("\n\n");

        // Deliberately judging from execText (≈finalText) alone again, not the round's full
        // tool_executions list — see docs/design/09-context-memory-compaction-design.md. The 08
        // redesign added reading the full list here specifically to stop Reflector trusting a
        // stale/misleading finalText; going back to finalText-only reintroduces that same risk
        // (a queried old record could again be misjudged as this round's new output) as a known,
        // deliberate trade-off for now, in exchange for a bounded prompt.
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
     * Rule: capabilities were selected but zero tools ran → cannot be FINISH.
     */
    private ReflectionDecision evaluateGuardRules(RoundRecord round, TaskExecutionState state) {
        PlanOutput plan = round.getPlan();
        ExecutionOutput exec = round.getExecutionResult();

        boolean capsSelected = plan != null
                && plan.getSelectedCapabilityIds() != null
                && !plan.getSelectedCapabilityIds().isEmpty();
        boolean noToolsRan = exec == null
                || exec.getToolExecutions() == null
                || exec.getToolExecutions().isEmpty();

        if (capsSelected && noToolsRan) {
            int capsCount = plan.getSelectedCapabilityIds().size();
            ReflectionDecision decision = new ReflectionDecision();
            decision.setAction(ReflectionAction.CONTINUE);
            decision.setReason("Guard: " + capsCount + " capabilities selected but no tools executed this round");
            // No LLM call on this path — reuse reason verbatim rather than spending a call just to
            // rephrase it. Nothing was produced this round, so there's nothing else to report.
            decision.setRoundSummary(decision.getReason());
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

    private ReflectionDecision parseDecision(String text) {
        try {
            String json = extractJson(text);
            ReflectionDecision decision = MAPPER.readValue(json, ReflectionDecision.class);
            if (decision.getAction() == null) {
                decision.setAction(ReflectionAction.ESCALATE);
            }
            if (decision.getRoundSummary() == null || decision.getRoundSummary().isBlank()) {
                // Model produced valid JSON but skipped the field — never leave it null, the next
                // round's Planner unconditionally reads it (see TaskPlanner's history section).
                decision.setRoundSummary(decision.getReason());
            }
            return decision;
        } catch (Exception e) {
            log.warn("Failed to parse ReflectionDecision, defaulting to ESCALATE: {}", e.getMessage());
            ReflectionDecision fallback = new ReflectionDecision();
            fallback.setAction(ReflectionAction.ESCALATE);
            fallback.setReason("parse error: " + e.getMessage());
            fallback.setRoundSummary("Reflector output could not be parsed: " + e.getMessage());
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

    private RoundRecord currentRound(TaskExecutionState state) {
        List<RoundRecord> rounds = state.getRounds();
        return rounds.get(rounds.size() - 1);
    }

    @Override
    public void stop() {
        // LLM call is synchronous; stop signal is checked between rounds
    }
}
