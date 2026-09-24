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
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityType;
import org.salt.regnexe.agent.core.common.enums.ExecutionStatus;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.common.util.RoundRecords;
import org.salt.regnexe.agent.core.event.AgentEvent;
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.agent.core.llm.ModelProvider;
import org.salt.regnexe.agent.core.llm.ModelSpec;
import org.salt.regnexe.agent.core.marketplace.Marketplace;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.execution.ExecutionOutput;
import org.salt.regnexe.agent.core.task.state.execution.ToolExecutionRecord;
import org.salt.regnexe.agent.core.task.state.plan.PlanOutput;
import org.salt.regnexe.agent.core.task.store.TaskStore;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.AgentAbortException;
import org.salt.jlangchain.core.agent.AgentStoppedException;
import org.salt.jlangchain.core.agent.McpAgentExecutor;
import org.salt.jlangchain.core.agent.memory.AgentContext;
import org.salt.jlangchain.core.agent.memory.AgentStep;
import org.salt.jlangchain.core.agent.memory.AgentTaskContext;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.skill.ReferenceDoc;
import org.salt.jlangchain.core.skill.ReferencesMode;
import org.salt.jlangchain.core.skill.ScriptDef;
import org.salt.jlangchain.core.skill.ScriptTool;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.subagent.SubAgent;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;

import java.util.ArrayList;
import java.util.HashMap;
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

    private static final int MAX_EXECUTE_RETRIES = 2;

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
        Integer maxConsecutiveToolFailures = bus.getTransmit(ContextBusKeys.MAX_CONSECUTIVE_TOOL_FAILURES);
        boolean verbose = Boolean.TRUE.equals(bus.getTransmit(ContextBusKeys.VERBOSE));
        String projectMemory = bus.getTransmit(ContextBusKeys.PROJECT_MEMORY);
        Set<String> baseToolNames = bus.getTransmit(ContextBusKeys.BASE_TOOL_NAMES);
        if (baseToolNames == null) baseToolNames = Set.of();

        BaseChatModel llm = llmProvider.provide(modelSpec);

        int round = state.getCurrentRound();
        String taskId = state.getTaskId();
        state.setLastToolResult(null);
        // This round's own tool calls only — labeled and scoped to this round, for the live event
        // log and Reflector's cross-check. Cross-round history is a separate concern, carried by
        // the shared AgentTaskContext built below (state.priorSteps/priorStepsSummary).
        List<ToolExecutionRecord> toolExecutions = new ArrayList<>();

        List<Tool> mcpTools = new ArrayList<>();
        List<SubAgent> subAgents = new ArrayList<>();
        // One entry per selected skill (see resolveCapabilities() SKILL branch), folded into
        // agentInput below instead of a separate systemPrompt slot — McpAgentExecutor has none.
        List<String> skillSystemPrompts = new ArrayList<>();
        // Name -> CapabilityType, so logs can prefix each tool call with "<type>:<name>"
        // (mcp_tool/skill/subagent). Names not present here (e.g. a sub-agent's own tools) get
        // no type prefix.
        Map<String, CapabilityType> typeByName = new HashMap<>();
        java.nio.file.Path claudeCompatWorkspace = bus.getTransmit(ContextBusKeys.CLAUDE_COMPAT_WORKSPACE);
        resolveCapabilities(marketplace, selectedCapIds, chainActor, llm, llmProvider, mcpTools, skillSystemPrompts,
                subAgents, maxAgentIterations, maxConsecutiveToolFailures, listener, taskId, round, verbose,
                toolExecutions, typeByName, claudeCompatWorkspace, baseToolNames);

        // Best-effort attribution for the tool-call log: a Skill shares this one loop/tool list
        // with everything else picked this round, so there's no real signal for which skill's
        // instructions motivated a given call. When exactly one skill is selected (the common
        // case), attribute every call in the round to it; otherwise leave the scope unset.
        List<String> selectedSkillNames = typeByName.entrySet().stream()
                .filter(e -> e.getValue() == CapabilityType.SKILL)
                .map(Map.Entry::getKey)
                .toList();
        String sharedLoopScope = selectedSkillNames.size() == 1
                ? "[skill:" + selectedSkillNames.get(0) + "]" : null;

        PlanOutput plan = RoundRecords.current(state).getPlan();
        AtomicReference<String> outerToolCall = new AtomicReference<>();
        McpAgentExecutor.Builder executorBuilder = McpAgentExecutor.builder(chainActor)
                .llm(llm)
                .tools(mcpTools)
                .subAgents(subAgents)
                .context(agentContext)
                .onLlm(text -> listener.dispatch(AgentEvent.of(taskId, round, EventType.LLM_RESPONDED, text)))
                .onToolCall(tc -> {
                    outerToolCall.set(tc);
                    listener.dispatch(AgentEvent.of(taskId, round, EventType.TOOL_CALLED,
                            labelToolCall(sharedLoopScope, tc, typeByName)));
                })
                .onObservation(obs -> {
                    state.setLastToolResult(obs);
                    String label = formatToolCallLabel(sharedLoopScope, outerToolCall.get(), typeByName);
                    recordToolExecution(toolExecutions, round, label, outerToolCall.get(), obs);
                    listener.dispatch(AgentEvent.of(taskId, round, EventType.TOOL_RESULT,
                            formatToolResult(label, obs)));
                })
                .onTokenUsage(u -> listener.dispatch(AgentEvent.ofTokenUsage(taskId, round, u)));
        if (maxAgentIterations != null) {
            executorBuilder.maxIterations(maxAgentIterations);
        }
        if (maxConsecutiveToolFailures != null) {
            executorBuilder.maxConsecutiveToolFailures(maxConsecutiveToolFailures);
        }
        McpAgentExecutor executor = executorBuilder.build();

        this.mcpAgentExecutor = executor;

        String agentInput = buildAgentInput(state, narrative, inputDescs, plan, projectMemory, skillSystemPrompts);

        listener.dispatch(AgentEvent.of(taskId, round, EventType.EXECUTION_STARTED,
                "Selected: " + selectedCapIds + " | " + agentInput));

        // Shared cross-round context: replay whatever steps/summary survived prior rounds into a
        // fresh AgentTaskContext, then hand it to invoke() as the preloaded context instead of
        // letting the executor create an empty one. originalTask is the overall goal (stable
        // across rounds) — this round's own agentInput is delivered separately below via
        // invoke()'s addHumanTurn path, so it isn't duplicated as both originalTask and a
        // replayed turn. Compaction of old steps into priorStepsSummary happens inside this
        // AgentTaskContext itself (e.g. SlidingWindowContext) — nothing here decides when to
        // compact.
        String originalTask = state.getRequest().getGoal();
        if (originalTask == null || originalTask.isBlank()) originalTask = agentInput;
        AgentTaskContext ctx = agentContext.create(originalTask, null);
        String priorSummary = state.getPriorStepsSummary();
        if (priorSummary != null && !priorSummary.isBlank()) {
            ctx.restoreSummary(priorSummary);
        }
        List<AgentStep> priorSteps = state.getPriorSteps();
        if (priorSteps != null) {
            priorSteps.forEach(ctx::addStep);
        }

        ExecutionOutput output = new ExecutionOutput();
        try {
            // Retries the WHOLE tool-calling loop, unlike Planner/Reflector's retry (a single
            // stateless LLM call, safe to just redo). This one can have already-committed side
            // effects (a write_file, a db insert) before the failure — replaying from the original
            // input risks a duplicate write. Reusing the same ctx across attempts softens that: a
            // retry after a partial failure sees its own earlier steps already in the transcript,
            // so it's less likely to redo work that already succeeded. Accepted for now: a failure
            // that kills the round outright already loses that same progress today (Reflector sees
            // this round's own tool calls and can tell CONTINUE to redo only what's missing), so a
            // bounded retry trades a small duplicate-write risk for a real chance of avoiding that
            // round-level do-over.
            ChatGeneration result = null;
            for (int attempt = 0; ; attempt++) {
                try {
                    result = executor.invoke(agentInput, stopSignal, ctx);
                    break;
                } catch (AgentStoppedException | AgentAbortException e) {
                    throw e;
                } catch (Exception e) {
                    if (attempt >= MAX_EXECUTE_RETRIES) throw e;
                    log.warn("Round {}: execute call failed on attempt {}/{}, retrying: {}",
                            round, attempt + 1, MAX_EXECUTE_RETRIES + 1, e.getMessage());
                }
            }
            String executionText = result.getText();
            output.setFinalText(executionText);
            output.setStatus(ExecutionStatus.SUCCESS);
            listener.dispatch(AgentEvent.of(taskId, round, EventType.EXECUTION_COMPLETED,
                    "SUCCESS | " + executionText));
            log.debug("Round {}: execution succeeded", state.getCurrentRound());
        } catch (AgentStoppedException e) {
            output.setFinalText(state.getLastToolResult() != null
                    ? state.getLastToolResult() : "Paused before any tool result was produced.");
            output.setStatus(ExecutionStatus.STOPPED);
            state.setStatus(TaskStatus.PAUSED);
            listener.dispatch(AgentEvent.of(taskId, round, EventType.EXECUTION_COMPLETED, "PAUSED"));
            log.debug("Round {}: execution paused", state.getCurrentRound());
        } catch (AgentAbortException e) {
            output.setStatus(ExecutionStatus.FAILED);
            // finalText is a short, honest marker, not a reconstruction of what happened — the
            // round's real tool-call detail already lives in this round's ExecutionOutput.toolExecutions
            // (and the shared ctx persisted below). The full e.getMessage() still reaches the live
            // event log two lines below, for a human watching in real time.
            output.setFinalText("Round " + round + " incomplete: iteration budget ("
                    + (maxAgentIterations != null ? maxAgentIterations : "?") + " steps) exceeded.");
            listener.dispatch(AgentEvent.of(taskId, round, EventType.EXECUTION_COMPLETED,
                    "FAILED | " + e.getMessage()));
            log.warn("Round {}: execution aborted: {}", state.getCurrentRound(), e.getMessage());
        } catch (Exception e) {
            output.setStatus(ExecutionStatus.FAILED);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName() + " (no message)";
            output.setFinalText(msg);
            listener.dispatch(AgentEvent.of(taskId, round, EventType.EXECUTION_COMPLETED, "FAILED | " + msg));
            log.warn("Round {}: execution failed: {}", state.getCurrentRound(), msg);
        } finally {
            this.mcpAgentExecutor = null;
        }
        output.setToolExecutions(toolExecutions);

        // Unconditional — success or failure — so next round replays whatever steps actually
        // completed before a failure, instead of starting over with nothing. ctx is the same
        // object McpAgentExecutor mutated internally (addStep/compaction), whether invoke()
        // returned normally or threw.
        state.setPriorSteps(new ArrayList<>(ctx.getCompletedSteps()));
        String updatedSummary = ctx.getEarlyStepsSummary();
        if (updatedSummary != null) {
            state.setPriorStepsSummary(updatedSummary);
        }

        // Unconditional — success or failure — so Reflector never reads a stale value left over
        // from an earlier round (previously this was only set in the success branch).
        bus.putTransmit(ContextBusKeys.EXEC_TEXT, output.getFinalText());

        RoundRecords.current(state).setExecutionResult(output);
        state.setUpdatedAt(System.currentTimeMillis());
        if (taskStore != null) taskStore.save(state);
        return null;
    }

    private String buildAgentInput(TaskExecutionState state, String narrative, Map<String, String> inputDescs,
                                   PlanOutput plan, String projectMemory, List<String> skillSystemPrompts) {
        StringBuilder sb = new StringBuilder();
        // Long-term project memory (REX.md) — same content Planner saw, repeated here since
        // Execute is a separate LLM call and agentInput is its only prompt (no systemPrompt slot).
        if (projectMemory != null && !projectMemory.isBlank()) {
            sb.append("Project memory:\n").append(projectMemory).append("\n\n");
        }
        // Each entry already carries its own "### Skill: <name>" heading (buildSkillSystemPrompt())
        // so multiple skills selected in the same round stay distinguishable.
        if (skillSystemPrompts != null && !skillSystemPrompts.isEmpty()) {
            sb.append("Skill instructions:\n");
            for (String skillPrompt : skillSystemPrompts) {
                sb.append(skillPrompt).append("\n\n");
            }
        }
        String goal = state.getRequest().getGoal();
        if (goal != null && !goal.isBlank()) {
            sb.append("Goal:\n").append(goal).append("\n\n");
        }
        sb.append("Plan:\n").append(narrative != null ? narrative : "");
        if (inputDescs != null && !inputDescs.isEmpty()) {
            sb.append("\n\nCapability input guidance:\n");
            inputDescs.forEach((id, desc) ->
                    sb.append("- ").append(id).append(": ").append(desc).append("\n"));
        }
        sb.append("\n\nFinal answer rule:\n")
                .append("Be concise. ")
                .append("Use all relevant capability results observed during execution. ")
                .append("Do not omit earlier capability results just because a later capability produced a long answer. ")
                .append("Synthesize a final answer that satisfies every final answer requirement.");
        if (plan != null && plan.getFinalAnswerRequirements() != null && !plan.getFinalAnswerRequirements().isEmpty()) {
            sb.append("\n\nFinal answer requirements:\n");
            plan.getFinalAnswerRequirements().forEach(req -> sb.append("- ").append(req).append("\n"));
        }
        return sb.toString();
    }

    /**
     * Separates selected capabilities into MCP tools and SubAgents, and merges selected Skills'
     * instructions/scripts directly into this round's shared tool list instead of spawning an
     * isolated executor per skill. Also resolves each skill/subagent's declared allowedTools from
     * the marketplace into mcpTools so the shared executor can call them directly.
     *
     * <p>Skill vs SubAgent tool access is deliberately asymmetric. A Skill runs in the same
     * context as the main agent, so it shares its full tool access — {@code allowedTools} only
     * names extra tools to resolve on top of that. A SubAgent is a real isolation boundary,
     * scoped to exactly its own tools plus explicitly allow-listed parent tools ({@code
     * SubAgent.collectTools()} in j-langchain). So every selected SKILL unconditionally gets
     * {@code baseToolNames} regardless of its own allowedTools declaration; SUB_AGENT does not.
     */
    private void resolveCapabilities(Marketplace marketplace, List<String> capIds,
                                     ChainActor chainActor, BaseChatModel llm, ModelProvider llmProvider,
                                     List<Tool> mcpTools, List<String> skillSystemPrompts, List<SubAgent> subAgents,
                                     Integer maxIterations, Integer maxConsecutiveToolFailures,
                                     AgentEventListener listener, String taskId, int round, boolean verbose,
                                     List<ToolExecutionRecord> toolExecutions,
                                     Map<String, CapabilityType> typeByName,
                                     java.nio.file.Path claudeCompatWorkspace,
                                     Set<String> baseToolNames) {
        if (marketplace == null || capIds == null) return;

        // Tool names already present in mcpTools, so later additions don't register a name twice.
        Set<String> existingToolNames = new HashSet<>();
        // Tool names to resolve from the marketplace and add to mcpTools once, after the main
        // loop: each selected skill's declared allowedTools plus baseToolNames (see class javadoc),
        // merged with subAgents' allowedTools below into one reconciliation pass.
        Set<String> skillGrantedToolNames = new HashSet<>();

        Set<String> seenCapIds = new HashSet<>();
        for (String capId : capIds) {
            if (!seenCapIds.add(capId)) continue;  // skip duplicate capability registrations
            CapabilityDescriptor cap = marketplace.resolveDescriptor(capId);
            if (cap == null) {
                log.warn("Capability not found: {}", capId);
                continue;
            }
            switch (cap.getType()) {
                case MCP_TOOL -> {
                    mcpTools.add(cap.getTool());
                    if (cap.getTool() != null) {
                        existingToolNames.add(cap.getTool().getName());
                        typeByName.put(cap.getTool().getName(), CapabilityType.MCP_TOOL);
                    }
                }
                case SKILL -> {
                    if (cap.getSkillConfig() != null) {
                        typeByName.put(cap.getName(), CapabilityType.SKILL);
                        SkillConfig skillConfig = cap.getSkillConfig();
                        skillSystemPrompts.add(buildSkillSystemPrompt(cap.getName(), skillConfig));

                        if (skillConfig.getScripts() != null) {
                            for (ScriptDef def : skillConfig.getScripts()) {
                                Tool scriptTool = ScriptTool.from(def);
                                if (existingToolNames.add(scriptTool.getName())) {
                                    mcpTools.add(scriptTool);
                                }
                            }
                        }
                        if (hasLazyReferences(skillConfig)) {
                            Tool readRefTool = buildReadReferenceTool(cap.getName(), skillConfig);
                            if (existingToolNames.add(readRefTool.getName())) {
                                mcpTools.add(readRefTool);
                            }
                        }
                        // A Skill always shares the main agent's base tool access, regardless of
                        // its own allowedTools declaration.
                        skillGrantedToolNames.addAll(baseToolNames);
                        List<String> declaredAllowed = skillConfig.getAllowedTools();
                        if (declaredAllowed != null && !declaredAllowed.isEmpty()) {
                            // Extra named tools this skill needs beyond the base set — resolved by
                            // name below, same mechanism as baseToolNames.
                            skillGrantedToolNames.addAll(declaredAllowed);
                        }
                    }
                }
                case SUB_AGENT -> {
                    if (cap.getSubAgentConfig() != null) {
                        typeByName.put(cap.getName(), CapabilityType.SUB_AGENT);
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
                            String scope = "[" + typeLabel(CapabilityType.SUB_AGENT) + ":" + name + "]";
                            AtomicReference<String> subAgentToolCall = new AtomicReference<>();
                            ab.onLlm(text -> listener.dispatch(
                                AgentEvent.of(taskId, round, EventType.AGENT_LLM_RESPONDED,
                                              scope + " " + text)));
                            ab.onToolCall(tc -> {
                                subAgentToolCall.set(tc);
                                listener.dispatch(AgentEvent.of(taskId, round, EventType.TOOL_CALLED,
                                        labelToolCall(scope, tc, typeByName)));
                            });
                            ab.onObservation(obs -> listener.dispatch(
                                AgentEvent.of(taskId, round, EventType.TOOL_RESULT,
                                              formatObservedToolResult(toolExecutions, round, scope, subAgentToolCall.get(), obs, typeByName))));
                        }
                        String agentCapName = cap.getName();
                        ab.onTokenUsage(u -> listener.dispatch(
                            AgentEvent.ofCapabilityTokenUsage(taskId, round, agentCapName, u)));
                        if (maxIterations != null) ab.maxIterations(maxIterations);
                        if (maxConsecutiveToolFailures != null) ab.maxConsecutiveToolFailures(maxConsecutiveToolFailures);
                        subAgents.add(ab.build());
                    } else if (cap.getTool() != null) {
                        mcpTools.add(cap.getTool());
                        existingToolNames.add(cap.getTool().getName());
                    }
                }
            }
        }

        // Make sure each skill's granted tools and each subagent's allowedTools are present in
        // mcpTools so the shared executor (skills) / McpAgentExecutor.build() (subagents) can
        // call/inject them.
        Set<String> allAllowed = new HashSet<>(skillGrantedToolNames);
        subAgents.forEach(a -> allAllowed.addAll(a.getAllowedTools()));

        for (String toolName : allAllowed) {
            if (existingToolNames.contains(toolName)) continue;
            CapabilityDescriptor dep = marketplace.resolveDescriptor(toolName);
            if (dep != null && dep.getType() == CapabilityType.MCP_TOOL
                    && dep.getTool() != null) {
                mcpTools.add(dep.getTool());
                existingToolNames.add(toolName);
                typeByName.put(dep.getTool().getName(), CapabilityType.MCP_TOOL);
            }
        }
    }

    /**
     * Builds one Skill's system-prompt-equivalent text: its own {@code systemPrompt} plus
     * references (INLINE mode: full content concatenated; LAZY mode: filename+summary manifest,
     * paired with the read_reference tool from {@link #buildReadReferenceTool}). Duplicates the
     * relevant slice of {@code Skill.buildSystemPrompt()} (j-langchain) rather than widening that
     * method's visibility. Prefixed with a "### Skill: &lt;name&gt;" heading so multiple skills
     * selected in the same round stay distinguishable in the merged prompt.
     */
    private String buildSkillSystemPrompt(String skillName, SkillConfig config) {
        StringBuilder sb = new StringBuilder("### Skill: ").append(skillName).append("\n");
        if (config.getSystemPrompt() != null && !config.getSystemPrompt().isBlank()) {
            sb.append(config.getSystemPrompt());
        }
        List<ReferenceDoc> refs = config.getReferences();
        if (refs != null && !refs.isEmpty()) {
            sb.append("\n\n---\n\n");
            if (config.getReferencesMode() == ReferencesMode.LAZY) {
                sb.append("Available reference documents (read on demand via read_reference_")
                        .append(skillName).append("):\n");
                for (ReferenceDoc ref : refs) {
                    sb.append("- ").append(ref.filename());
                    if (ref.summary() != null && !ref.summary().isBlank()) {
                        sb.append(": ").append(ref.summary());
                    }
                    sb.append("\n");
                }
            } else {
                for (int i = 0; i < refs.size(); i++) {
                    if (i > 0) sb.append("\n\n---\n\n");
                    sb.append(refs.get(i).content());
                }
            }
        }
        return sb.toString();
    }

    private boolean hasLazyReferences(SkillConfig config) {
        return config.getReferencesMode() == ReferencesMode.LAZY
                && config.getReferences() != null && !config.getReferences().isEmpty();
    }

    /**
     * Mirrors {@code Skill.buildReadReferenceTool()} (j-langchain, package-private) — duplicated
     * for the same reason as {@link #buildSkillSystemPrompt}. Tool name is namespaced with the
     * skill name so multiple lazy-reference skills selected in the same round don't collide.
     */
    private Tool buildReadReferenceTool(String skillName, SkillConfig config) {
        Map<String, String> byFilename = new HashMap<>();
        for (ReferenceDoc ref : config.getReferences()) {
            byFilename.putIfAbsent(ref.filename(), ref.content());
        }
        return Tool.builder()
                .name("read_reference_" + skillName)
                .description("Read the full content of a '" + skillName + "' skill reference document by filename. "
                        + "Available files: " + String.join(", ", byFilename.keySet()))
                .params("file: String")
                .func(raw -> {
                    String file = argString(raw, "file");
                    String content = byFilename.get(file);
                    return content != null ? content : "Error: reference not found: " + file;
                })
                .build();
    }

    private static String argString(Object raw, String key) {
        if (!(raw instanceof Map<?, ?> map)) return "";
        Object v = map.get(key);
        return v != null ? v.toString().trim() : "";
    }

    /** Renders a CapabilityType as its log prefix: mcp_tool / skill / subagent. */
    private String typeLabel(CapabilityType type) {
        return switch (type) {
            case MCP_TOOL -> "mcp_tool";
            case SKILL -> "skill";
            case SUB_AGENT -> "subagent";
        };
    }

    /**
     * Builds the "<scope> <type>:<name>" label for a tool call (no arguments).
     * The "<type>:" prefix is added only when the invoked name is a registered
     * capability; unknown names (e.g. a sub-agent's private own-tools) keep the bare name.
     */
    private String formatToolCallLabel(String scope, String toolCallText, Map<String, CapabilityType> typeByName) {
        String name = extractToolName(toolCallText);
        CapabilityType type = typeByName.get(name);
        String typed = type != null ? typeLabel(type) + ":" + name : name;
        return scope == null || scope.isBlank() ? typed : scope + " " + typed;
    }

    /** Full tool-call log line: "<scope> <type>:<name> <args-json>". */
    private String labelToolCall(String scope, String toolCallText, Map<String, CapabilityType> typeByName) {
        String label = formatToolCallLabel(scope, toolCallText, typeByName);
        String args = extractToolArguments(toolCallText);
        return args.isBlank() ? label : label + " " + args;
    }

    private String formatObservedToolResult(List<ToolExecutionRecord> records, int round,
                                            String scope, String rawToolCall, String observation,
                                            Map<String, CapabilityType> typeByName) {
        String label = formatToolCallLabel(scope, rawToolCall, typeByName);
        recordToolExecution(records, round, label, rawToolCall, observation);
        return formatToolResult(label, observation);
    }

    private void recordToolExecution(List<ToolExecutionRecord> records, int round,
                                     String toolCallLabel, String rawToolCall, String observation) {
        ToolExecutionRecord record = new ToolExecutionRecord();
        record.setRound(round);
        record.setToolName(toolCallLabel == null || toolCallLabel.isBlank() ? "unknown" : toolCallLabel);
        record.setToolCall(toolCallLabel);
        record.setArguments(extractToolArguments(rawToolCall));
        record.setObservation(observation);
        record.setTimestamp(System.currentTimeMillis());
        records.add(record);
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

    private String extractToolArguments(String toolCallText) {
        if (toolCallText == null || toolCallText.isBlank()) {
            return "";
        }
        int jsonStart = toolCallText.indexOf('{');
        return jsonStart >= 0 ? toolCallText.substring(jsonStart).trim() : "";
    }

    private String formatToolResult(String toolCallLabel, String observation) {
        if (toolCallLabel == null || toolCallLabel.isBlank()) {
            return observation;
        }
        return toolCallLabel + " -> " + observation;
    }

    @Override
    public void stop() {
        McpAgentExecutor executor = this.mcpAgentExecutor;
        if (executor != null) {
            executor.stop();
        }
    }
}
