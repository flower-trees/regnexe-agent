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
import org.salt.function.flow.context.IContextBus;
import org.salt.function.flow.node.FlowNode;
import org.salt.regnexe.agent.core.common.enums.CapabilityType;
import org.salt.regnexe.agent.core.common.enums.ExecutionStatus;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.AgentEvent;
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.agent.core.llm.ModelProvider;
import org.salt.regnexe.agent.core.llm.ModelSpec;
import org.salt.regnexe.agent.core.market.Marketplace;
import org.salt.regnexe.agent.core.market.plugin.CapabilityDescriptor;
import org.salt.regnexe.agent.core.task.state.RoundRecord;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.execution.ExecutionOutput;
import org.salt.regnexe.agent.core.task.state.plan.PlanOutput;
import org.salt.regnexe.agent.core.task.state.plan.ResultStrategy;
import org.salt.regnexe.agent.core.task.store.TaskStore;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.AgentStoppedException;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.agent.memory.AgentContext;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.skill.Skill;
import org.salt.jlangchain.core.subagent.SubAgent;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Delegates plan execution to McpAgentExecutor.
 * Holds a reference to the inner executor so stop() can cascade the signal.
 */
@Slf4j
public class CapabilityExecutor extends FlowNode<Object, Object> implements Worker {

    private volatile McpAgentExecutor mcpAgentExecutor;

    @Override
    public Object process(Object input) {
        IContextBus bus = getContextBus();
        TaskExecutionState state = bus.getTransmit(ContextBusKeys.STATE);
        if (state.getStatus() != TaskStatus.RUNNING) {
            log.debug("CapabilityExecutor skipped because task status is {}", state.getStatus());
            return null;
        }
        ChainActor chainActor = bus.getTransmit(ContextBusKeys.CHAIN_ACTOR);
        ModelProvider llmProvider = bus.getTransmit(ContextBusKeys.LLM_PROVIDER);
        ModelSpec modelSpec = bus.getTransmit(ContextBusKeys.DEFAULT_MODEL);
        AgentEventListener listener = bus.getTransmit(ContextBusKeys.EVENT_LISTENER);
        Marketplace marketplace = bus.getTransmit(ContextBusKeys.MARKETPLACE);

        String narrative = bus.getTransmit(ContextBusKeys.PLAN_NARRATIVE);
        List<String> selectedCapIds = bus.getTransmit(ContextBusKeys.SELECTED_CAPS);
        Map<String, String> inputDescs = bus.getTransmit(ContextBusKeys.CAPABILITY_INPUT_DESCS);
        AtomicBoolean stopSignal = bus.getTransmit(ContextBusKeys.STOP_SIGNAL);
        AgentContext agentContext = bus.getTransmit(ContextBusKeys.AGENT_CONTEXT);
        TaskStore taskStore = bus.getTransmit(ContextBusKeys.TASK_STORE);
        Integer maxAgentIterations = bus.getTransmit(ContextBusKeys.MAX_AGENT_ITERATIONS);
        boolean verbose = Boolean.TRUE.equals(bus.getTransmit(ContextBusKeys.VERBOSE));

        BaseChatModel llm = llmProvider.provide(modelSpec);

        int round = state.getCurrentRound();
        String taskId = state.getTaskId();
        state.setLastToolResult(null);

        List<Tool> mcpTools = new ArrayList<>();
        List<Skill> skills = new ArrayList<>();
        List<SubAgent> subAgents = new ArrayList<>();
        resolveCapabilities(marketplace, selectedCapIds, chainActor, llm, llmProvider, mcpTools, skills, subAgents,
                maxAgentIterations, listener, taskId, round, verbose);
        PlanOutput plan = currentRound(state).getPlan();
        ResultStrategy resultStrategy = resolveResultStrategy(plan);
        boolean returnLastToolResult = resultStrategy == ResultStrategy.RETURN_LAST;
        AtomicReference<String> outerToolCall = new AtomicReference<>();
        McpAgentExecutor.Builder executorBuilder = McpAgentExecutor.builder(chainActor)
                .llm(llm)
                .tools(mcpTools)
                .skills(skills)
                .subAgents(subAgents)
                .context(agentContext)
                .onLlm(text -> listener.dispatch(AgentEvent.of(taskId, round, EventType.LLM_RESPONDED, text)))
                .onToolCall(tc -> {
                    outerToolCall.set(formatToolCallLabel(null, tc));
                    listener.dispatch(AgentEvent.of(taskId, round, EventType.TOOL_CALLED, tc));
                })
                .onObservation(obs -> {
                    state.setLastToolResult(obs);
                    listener.dispatch(AgentEvent.of(taskId, round, EventType.TOOL_RESULT,
                            formatToolResult(outerToolCall.get(), obs)));
                })
                .returnLastToolResult(returnLastToolResult)
                .onTokenUsage(u -> listener.dispatch(AgentEvent.ofTokenUsage(taskId, round, u)));
        if (maxAgentIterations != null) {
            executorBuilder.maxIterations(maxAgentIterations);
        }
        McpAgentExecutor executor = executorBuilder.build();

        this.mcpAgentExecutor = executor;

        String agentInput = buildAgentInput(state.getRequest().getGoal(), narrative, inputDescs, plan, resultStrategy);

        listener.dispatch(AgentEvent.of(taskId, round, EventType.EXECUTION_STARTED,
                "Selected: " + selectedCapIds + " | Strategy: " + resultStrategy + " | " + agentInput));

        ExecutionOutput output = new ExecutionOutput();
        try {
            ChatGeneration result = executor.invoke(agentInput, stopSignal);
            String executionText = returnLastToolResult && state.getLastToolResult() != null
                    ? state.getLastToolResult()
                    : result.getText();
            output.setFinalText(executionText);
            output.setStatus(ExecutionStatus.SUCCESS);
            bus.putTransmit(ContextBusKeys.EXEC_TEXT, executionText);
            listener.dispatch(AgentEvent.of(taskId, round, EventType.EXECUTION_COMPLETED,
                    "SUCCESS | " + executionText));
            log.debug("Round {}: execution succeeded", state.getCurrentRound());
        } catch (AgentStoppedException e) {
            output.setStatus(ExecutionStatus.STOPPED);
            state.setStatus(TaskStatus.PAUSED);
            listener.dispatch(AgentEvent.of(taskId, round, EventType.EXECUTION_COMPLETED, "PAUSED"));
            log.debug("Round {}: execution paused", state.getCurrentRound());
        } catch (Exception e) {
            output.setStatus(ExecutionStatus.FAILED);
            output.setFinalText(e.getMessage());
            listener.dispatch(AgentEvent.of(taskId, round, EventType.EXECUTION_COMPLETED,
                    "FAILED | " + e.getMessage()));
            log.warn("Round {}: execution failed: {}", state.getCurrentRound(), e.getMessage());
        } finally {
            this.mcpAgentExecutor = null;
        }

        currentRound(state).setExecutionResult(output);
        state.setUpdatedAt(System.currentTimeMillis());
        if (taskStore != null) taskStore.save(state);
        return null;
    }

    private ResultStrategy resolveResultStrategy(PlanOutput plan) {
        if (plan == null || plan.getResultStrategy() == null) {
            return ResultStrategy.SYNTHESIZE;
        }
        return plan.getResultStrategy();
    }

    private String buildAgentInput(String goal, String narrative, Map<String, String> inputDescs,
                                   PlanOutput plan, ResultStrategy resultStrategy) {
        StringBuilder sb = new StringBuilder();
        if (goal != null && !goal.isBlank()) {
            sb.append("Original goal:\n").append(goal).append("\n\n");
        }
        sb.append("Execution plan:\n").append(narrative != null ? narrative : "");
        if (inputDescs != null && !inputDescs.isEmpty()) {
            sb.append("\n\nCapability input guidance:\n");
            inputDescs.forEach((id, desc) ->
                    sb.append("- ").append(id).append(": ").append(desc).append("\n"));
        }
        sb.append("\n\nFinal answer rule:\n")
                .append("Be concise. ");
        if (resultStrategy == ResultStrategy.RETURN_LAST) {
            sb.append("If the selected capability produces a complete answer, return that answer directly. ")
                    .append("Do not expand, reformat, or add extra detail beyond the selected capability result unless the user explicitly asks for detail.");
        } else {
            sb.append("Use all relevant tool results observed during execution. ")
                    .append("Do not omit earlier capability results just because a later capability produced a long answer. ")
                    .append("Synthesize a final answer that satisfies every final answer requirement.");
        }
        if (plan != null && plan.getFinalAnswerRequirements() != null && !plan.getFinalAnswerRequirements().isEmpty()) {
            sb.append("\n\nFinal answer requirements:\n");
            plan.getFinalAnswerRequirements().forEach(req -> sb.append("- ").append(req).append("\n"));
        }
        return sb.toString();
    }

    /**
     * Separates selected capabilities into MCP tools, Skills, and SubAgents.
     * Also resolves each skill/subagent's allowedTools from the marketplace and
     * adds them to mcpTools so McpAgentExecutor.build() can inject them correctly.
     */
    private void resolveCapabilities(Marketplace marketplace, List<String> capIds,
                                     ChainActor chainActor, BaseChatModel llm, ModelProvider llmProvider,
                                     List<Tool> mcpTools, List<Skill> skills, List<SubAgent> subAgents,
                                     Integer maxIterations,
                                     AgentEventListener listener, String taskId, int round, boolean verbose) {
        if (marketplace == null || capIds == null) return;

        Set<String> seenCapIds = new HashSet<>();
        for (String capId : capIds) {
            if (!seenCapIds.add(capId)) continue;  // skip duplicate capability registrations
            CapabilityDescriptor cap = marketplace.resolveDescriptor(capId);
            if (cap == null) {
                log.warn("Capability not found: {}", capId);
                continue;
            }
            switch (cap.getType()) {
                case MCP_TOOL -> mcpTools.add(cap.getTool());
                case SKILL -> {
                    if (cap.getSkillConfig() != null) {
                        Skill.Builder sb = Skill.from(cap.getSkillConfig(), chainActor).llm(llm);
                        if (verbose) {
                            sb.verbose(true);
                        } else {
                            String name = cap.getName();
                            String scope = "[skill:" + name + "]";
                            AtomicReference<String> skillToolCall = new AtomicReference<>();
                            sb.onLlm(text -> listener.dispatch(
                                AgentEvent.of(taskId, round, EventType.SKILL_LLM_RESPONDED,
                                              scope + " " + text)));
                            sb.onToolCall(tc -> {
                                skillToolCall.set(formatToolCallLabel(scope, tc));
                                listener.dispatch(AgentEvent.of(taskId, round, EventType.TOOL_CALLED,
                                        scope + " " + tc));
                            });
                            sb.onObservation(obs -> listener.dispatch(
                                AgentEvent.of(taskId, round, EventType.TOOL_RESULT,
                                              formatToolResult(skillToolCall.get(), obs))));
                        }
                        String skillCapName = cap.getName();
                        sb.onTokenUsage(u -> listener.dispatch(
                            AgentEvent.ofCapabilityTokenUsage(taskId, round, skillCapName, u)));
                        if (maxIterations != null) sb.maxIterations(maxIterations);
                        skills.add(sb.build());
                    }
                }
                case SUB_AGENT -> {
                    if (cap.getSubAgentConfig() != null) {
                        SubAgentConfig cfg = cap.getSubAgentConfig();
                        SubAgent.Builder ab = SubAgent.from(cfg, chainActor);
                        if (cfg.isInheritModel() || cfg.getModel() == null) {
                            ab.llm(llm);
                        } else {
                            java.util.Map<String, Object> capKwargs = cap.getModelKwargs();
                            ab.llmFactory(name -> {
                                int sep = name.indexOf(':');
                                ModelSpec spec = sep > 0
                                        ? ModelSpec.of(name.substring(0, sep), name.substring(sep + 1), capKwargs)
                                        : ModelSpec.of(null, name, capKwargs);
                                return llmProvider.provide(spec);
                            });
                        }
                        if (cfg.getOwnTools() != null && !cfg.getOwnTools().isEmpty()) {
                            ab.tools(cfg.getOwnTools());
                        }
                        if (verbose) {
                            ab.verbose(true);
                        } else {
                            String name = cap.getName();
                            String scope = "[subagent:" + name + "]";
                            AtomicReference<String> subAgentToolCall = new AtomicReference<>();
                            ab.onLlm(text -> listener.dispatch(
                                AgentEvent.of(taskId, round, EventType.AGENT_LLM_RESPONDED,
                                              scope + " " + text)));
                            ab.onToolCall(tc -> {
                                subAgentToolCall.set(formatToolCallLabel(scope, tc));
                                listener.dispatch(AgentEvent.of(taskId, round, EventType.TOOL_CALLED,
                                        scope + " " + tc));
                            });
                            ab.onObservation(obs -> listener.dispatch(
                                AgentEvent.of(taskId, round, EventType.TOOL_RESULT,
                                              formatToolResult(subAgentToolCall.get(), obs))));
                        }
                        String agentCapName = cap.getName();
                        ab.onTokenUsage(u -> listener.dispatch(
                            AgentEvent.ofCapabilityTokenUsage(taskId, round, agentCapName, u)));
                        if (maxIterations != null) ab.maxIterations(maxIterations);
                        subAgents.add(ab.build());
                    } else if (cap.getTool() != null) {
                        mcpTools.add(cap.getTool());
                    }
                }
            }
        }

        // Ensure each skill/subagent's allowedTools are present in mcpTools so that
        // McpAgentExecutor.build() can inject inherited tools correctly.
        Set<String> existingNames = new HashSet<>();
        mcpTools.forEach(t -> existingNames.add(t.getName()));

        Set<String> allAllowed = new HashSet<>();
        skills.forEach(s -> allAllowed.addAll(s.getAllowedTools()));
        subAgents.forEach(a -> allAllowed.addAll(a.getAllowedTools()));

        for (String toolName : allAllowed) {
            if (existingNames.contains(toolName)) continue;
            CapabilityDescriptor dep = marketplace.resolveDescriptor(toolName);
            if (dep != null && dep.getType() == CapabilityType.MCP_TOOL
                    && dep.getTool() != null) {
                mcpTools.add(dep.getTool());
                existingNames.add(toolName);
            }
        }
    }

    private String formatToolCallLabel(String scope, String toolCallText) {
        String name = extractToolName(toolCallText);
        return scope == null || scope.isBlank() ? name : scope + " " + name;
    }

    private String extractToolName(String toolCallText) {
        if (toolCallText == null || toolCallText.isBlank()) {
            return "unknown";
        }
        String text = toolCallText.trim();
        int jsonStart = text.indexOf('{');
        if (jsonStart > 0) {
            return text.substring(0, jsonStart).trim();
        }
        int space = text.indexOf(' ');
        return space > 0 ? text.substring(0, space).trim() : text;
    }

    private String formatToolResult(String toolCallLabel, String observation) {
        if (toolCallLabel == null || toolCallLabel.isBlank()) {
            return observation;
        }
        return toolCallLabel + " -> " + observation;
    }

    private RoundRecord currentRound(TaskExecutionState state) {
        List<RoundRecord> rounds = state.getRounds();
        return rounds.get(rounds.size() - 1);
    }

    @Override
    public void stop() {
        McpAgentExecutor executor = this.mcpAgentExecutor;
        if (executor != null) {
            executor.stop();
        }
    }
}
