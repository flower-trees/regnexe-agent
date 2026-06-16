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
import org.salt.regnexe.agent.core.task.state.reflection.ReflectionDecision;
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

    @Override
    public Object process(Object input) {
        IContextBus bus = getContextBus();
        TaskExecutionState state = bus.getTransmit(ContextBusKeys.STATE);
        ChainActor chainActor = bus.getTransmit(ContextBusKeys.CHAIN_ACTOR);
        ModelProvider llmProvider = bus.getTransmit(ContextBusKeys.LLM_PROVIDER);
        ModelSpec modelSpec = bus.getTransmit(ContextBusKeys.DEFAULT_MODEL);
        AgentEventListener listener = bus.getTransmit(ContextBusKeys.EVENT_LISTENER);
        TaskStore taskStore = bus.getTransmit(ContextBusKeys.TASK_STORE);

        String execText = bus.getTransmit(ContextBusKeys.EXEC_TEXT);

        BaseChatModel llm = llmProvider.provide(modelSpec);
        String taskId = state.getTaskId();
        int roundNum = state.getCurrentRound();
        FlowInstance flow = buildFlow(chainActor, llm,
                text -> listener.dispatch(AgentEvent.of(taskId, roundNum, EventType.REFLECT_LLM_RESPONDED, text)));

        listener.dispatch(AgentEvent.of(taskId, roundNum, EventType.REFLECTION_STARTED,
                execText != null ? execText : "(no execution output)"));

        String userPrompt = buildPrompt(state, execText);
        ChatGeneration result = chainActor.invoke(flow, Map.of("prompt", userPrompt));

        ReflectionDecision decision = parseDecision(result.getText());

        RoundRecord round = currentRound(state);
        round.setReflection(decision);
        round.setEndedAt(System.currentTimeMillis());

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

    private String buildPrompt(TaskExecutionState state, String execText) {
        StringBuilder sb = new StringBuilder();
        sb.append("Goal: ").append(state.getRequest().getGoal()).append("\n\n");

        RoundRecord round = currentRound(state);
        if (round.getPlan() != null) {
            sb.append("Plan narrative: ").append(round.getPlan().getNarrative()).append("\n\n");
        }

        if (round.getPlan() != null
                && round.getPlan().getCapabilityInputDescriptions() != null
                && !round.getPlan().getCapabilityInputDescriptions().isEmpty()) {
            sb.append("Capability input descriptions used this round:\n");
            round.getPlan().getCapabilityInputDescriptions()
                 .forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
            sb.append("\n");
        }

        sb.append("Execution result:\n");
        sb.append(execText != null ? execText : "(no output)").append("\n\n");

        List<RoundRecord> rounds = state.getRounds();
        if (rounds.size() > 1) {
            sb.append("This is round ").append(state.getCurrentRound())
              .append(" of max ").append(state.getMaxRounds()).append(".\n");
        }

        return sb.toString();
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

    private RoundRecord currentRound(TaskExecutionState state) {
        List<RoundRecord> rounds = state.getRounds();
        return rounds.get(rounds.size() - 1);
    }

    @Override
    public void stop() {
        // LLM call is synchronous; stop signal is checked between rounds
    }
}
