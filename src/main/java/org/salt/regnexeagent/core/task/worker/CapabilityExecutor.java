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

package org.salt.regnexeagent.core.task.worker;

import lombok.extern.slf4j.Slf4j;
import org.salt.function.flow.context.IContextBus;
import org.salt.function.flow.node.FlowNode;
import org.salt.regnexeagent.core.common.enums.ExecutionStatus;
import org.salt.regnexeagent.core.common.enums.TaskStatus;
import org.salt.regnexeagent.core.event.AgentEvent;
import org.salt.regnexeagent.core.event.AgentEventListener;
import org.salt.regnexeagent.core.event.EventType;
import org.salt.regnexeagent.core.llm.ModelProvider;
import org.salt.regnexeagent.core.llm.ModelSpec;
import org.salt.regnexeagent.core.market.Marketplace;
import org.salt.regnexeagent.core.market.plugin.CapabilityDescriptor;
import org.salt.regnexeagent.core.task.state.RoundRecord;
import org.salt.regnexeagent.core.task.state.TaskExecutionState;
import org.salt.regnexeagent.core.task.state.execution.ExecutionOutput;
import org.salt.regnexeagent.core.task.store.TaskStore;
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
import java.util.List;
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
        AtomicBoolean stopSignal = bus.getTransmit(ContextBusKeys.STOP_SIGNAL);
        AgentContext agentContext = bus.getTransmit(ContextBusKeys.AGENT_CONTEXT);
        TaskStore taskStore = bus.getTransmit(ContextBusKeys.TASK_STORE);

        BaseChatModel llm = llmProvider.provide(modelSpec);
        List<Tool> tools = resolveTools(marketplace, selectedCapIds, chainActor, llm);

        int round = state.getCurrentRound();
        String taskId = state.getTaskId();
        McpAgentExecutor executor = McpAgentExecutor.builder(chainActor)
                .llm(llm)
                .tools(tools)
                .context(agentContext)
                .onLlm(text -> listener.onEvent(AgentEvent.of(taskId, round, EventType.LLM_RESPONDED, text)))
                .onToolCall(tc -> listener.onEvent(AgentEvent.of(taskId, round, EventType.TOOL_CALLED, tc)))
                .onObservation(obs -> listener.onEvent(AgentEvent.of(taskId, round, EventType.TOOL_RESULT, obs)))
                .build();

        this.mcpAgentExecutor = executor;

        ExecutionOutput output = new ExecutionOutput();
        try {
            ChatGeneration result = executor.invoke(narrative, stopSignal);
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

    private List<Tool> resolveTools(Marketplace marketplace, List<String> capIds,
                                    ChainActor chainActor, BaseChatModel llm) {
        List<Tool> tools = new ArrayList<>();
        if (marketplace == null || capIds == null) return tools;
        for (String capId : capIds) {
            CapabilityDescriptor cap = marketplace.resolveDescriptor(capId);
            if (cap == null) {
                log.warn("Capability not found: {}", capId);
                continue;
            }
            Tool tool = buildTool(cap, chainActor, llm);
            if (tool != null) {
                tools.add(tool);
            }
        }
        return tools;
    }

    private Tool buildTool(CapabilityDescriptor cap, ChainActor chainActor, BaseChatModel llm) {
        return switch (cap.getType()) {
            case MCP_TOOL  -> cap.getTool();
            // If skillConfig is set (file-based plugin), build at execution time.
            // If tool is set (programmatically pre-built), use it directly.
            case SKILL     -> cap.getSkillConfig() != null
                    ? Skill.from(cap.getSkillConfig(), chainActor).llm(llm).build().asTool()
                    : cap.getTool();
            case SUB_AGENT -> cap.getSubAgentConfig() != null
                    ? SubAgent.from(cap.getSubAgentConfig(), chainActor).llm(llm).build().asTool()
                    : cap.getTool();
        };
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
