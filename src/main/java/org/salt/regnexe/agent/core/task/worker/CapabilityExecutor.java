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
import org.salt.regnexe.agent.core.task.store.TaskStore;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.AgentStoppedException;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.agent.memory.AgentContext;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.skill.Skill;
import org.salt.jlangchain.core.subagent.SubAgent;
import org.salt.jlangchain.rag.tools.Tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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

        List<Tool> mcpTools = new ArrayList<>();
        List<Skill> skills = new ArrayList<>();
        List<SubAgent> subAgents = new ArrayList<>();
        resolveCapabilities(marketplace, selectedCapIds, chainActor, llm, mcpTools, skills, subAgents,
                maxAgentIterations, listener, taskId, round, verbose);
        McpAgentExecutor.Builder executorBuilder = McpAgentExecutor.builder(chainActor)
                .llm(llm)
                .tools(mcpTools)
                .skills(skills)
                .subAgents(subAgents)
                .context(agentContext)
                .onLlm(text -> listener.onEvent(AgentEvent.of(taskId, round, EventType.LLM_RESPONDED, text)))
                .onToolCall(tc -> listener.onEvent(AgentEvent.of(taskId, round, EventType.TOOL_CALLED, tc)))
                .onObservation(obs -> listener.onEvent(AgentEvent.of(taskId, round, EventType.TOOL_RESULT, obs)))
                .onTokenUsage(u -> listener.onEvent(AgentEvent.ofTokenUsage(taskId, round, u)));
        if (maxAgentIterations != null) {
            executorBuilder.maxIterations(maxAgentIterations);
        }
        McpAgentExecutor executor = executorBuilder.build();

        this.mcpAgentExecutor = executor;

        ExecutionOutput output = new ExecutionOutput();
        try {
            ChatGeneration result = executor.invoke(buildAgentInput(narrative, inputDescs), stopSignal);
            output.setFinalText(result.getText());
            output.setStatus(ExecutionStatus.SUCCESS);
            bus.putTransmit(ContextBusKeys.EXEC_TEXT, result.getText());
            listener.onEvent(AgentEvent.of(taskId, round, EventType.EXECUTION_COMPLETED,
                    "SUCCESS | " + result.getText()));
            log.debug("Round {}: execution succeeded", state.getCurrentRound());
        } catch (AgentStoppedException e) {
            output.setStatus(ExecutionStatus.STOPPED);
            state.setStatus(TaskStatus.PAUSED);
            listener.onEvent(AgentEvent.of(taskId, round, EventType.EXECUTION_COMPLETED, "PAUSED"));
            log.debug("Round {}: execution paused", state.getCurrentRound());
        } catch (Exception e) {
            output.setStatus(ExecutionStatus.FAILED);
            output.setFinalText(e.getMessage());
            listener.onEvent(AgentEvent.of(taskId, round, EventType.EXECUTION_COMPLETED,
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

    private String buildAgentInput(String narrative, Map<String, String> inputDescs) {
        if (inputDescs == null || inputDescs.isEmpty()) return narrative;
        StringBuilder sb = new StringBuilder(narrative)
                .append("\n\nCapability input guidance:\n");
        inputDescs.forEach((id, desc) ->
                sb.append("- ").append(id).append(": ").append(desc).append("\n"));
        return sb.toString();
    }

    /**
     * Separates selected capabilities into MCP tools, Skills, and SubAgents.
     * Also resolves each skill/subagent's allowedTools from the marketplace and
     * adds them to mcpTools so McpAgentExecutor.build() can inject them correctly.
     */
    private void resolveCapabilities(Marketplace marketplace, List<String> capIds,
                                     ChainActor chainActor, BaseChatModel llm,
                                     List<Tool> mcpTools, List<Skill> skills, List<SubAgent> subAgents,
                                     Integer maxIterations,
                                     AgentEventListener listener, String taskId, int round, boolean verbose) {
        if (marketplace == null || capIds == null) return;

        for (String capId : capIds) {
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
                            sb.onLlm(text -> listener.onEvent(
                                AgentEvent.of(taskId, round, EventType.SKILL_LLM_RESPONDED,
                                              "[skill:" + name + "] " + text)));
                        }
                        if (maxIterations != null) sb.maxIterations(maxIterations);
                        skills.add(sb.build());
                    } else if (cap.getTool() != null) {
                        mcpTools.add(cap.getTool());
                    }
                }
                case SUB_AGENT -> {
                    if (cap.getSubAgentConfig() != null) {
                        SubAgent.Builder ab = SubAgent.from(cap.getSubAgentConfig(), chainActor).llm(llm);
                        if (verbose) {
                            ab.verbose(true);
                        } else {
                            String name = cap.getName();
                            ab.onLlm(text -> listener.onEvent(
                                AgentEvent.of(taskId, round, EventType.SKILL_LLM_RESPONDED,
                                              "[subagent:" + name + "] " + text)));
                        }
                        if (maxIterations != null) ab.maxIterations(maxIterations);
                        subAgents.add(ab.build());
                    } else if (cap.getTool() != null) {
                        mcpTools.add(cap.getTool());
                    }
                }
            }
        }

        // Ensure each skill/subagent's allowedTools are present in mcpTools so that
        // McpAgentExecutor.build() can inject them. These tools are internal to the
        // skill/subagent and filtered by allowedTools during injection.
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
