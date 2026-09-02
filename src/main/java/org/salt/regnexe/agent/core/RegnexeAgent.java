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

package org.salt.regnexe.agent.core;

import lombok.extern.slf4j.Slf4j;
import org.salt.function.flow.FlowEngine;
import org.salt.function.flow.FlowInstance;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityType;
import org.salt.regnexe.agent.core.common.enums.TaskStatus;
import org.salt.regnexe.agent.core.event.AgentEvent;
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.event.EventType;
import org.salt.regnexe.agent.core.llm.ModelProvider;
import org.salt.regnexe.agent.core.llm.ModelSpec;
import org.salt.regnexe.agent.core.marketplace.Marketplace;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.task.AgentResult;
import org.salt.regnexe.agent.core.task.ResultComposer;
import org.salt.regnexe.agent.core.task.state.RoundRecord;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.TaskRequest;
import org.salt.regnexe.agent.core.task.state.reflection.ReflectionDecision;
import org.salt.regnexe.agent.core.task.store.TaskStore;
import org.salt.regnexe.agent.core.task.worker.CapabilityExecutor;
import org.salt.regnexe.agent.core.task.worker.CapabilitySearcher;
import org.salt.regnexe.agent.core.task.worker.ContextBusKeys;
import org.salt.regnexe.agent.core.task.worker.Reflector;
import org.salt.regnexe.agent.core.task.worker.TaskPlanner;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.memory.AgentContext;
import org.salt.regnexe.agent.core.common.util.TextCompressor;
import org.salt.jlangchain.core.history.HistoryInfos;
import org.salt.jlangchain.core.history.memory.ConversationMemory;
import org.salt.jlangchain.core.history.memory.periodic.PeriodicConversationSummaryMemory;
import org.salt.jlangchain.core.history.memory.periodic.PeriodicConversationSummaryMemoryReader;
import org.salt.jlangchain.core.history.storage.ConversationStorage;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.message.BaseMessage;
import org.salt.jlangchain.core.message.MessageType;
import org.salt.jlangchain.core.skill.Skill;
import org.salt.jlangchain.rag.tools.Tool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RegnexeAgent Runtime — orchestrates the Search→Plan→Execute→Reflect loop.
 * Not a Spring bean; obtain instances via {@link RegnexeAgentBuilder}.
 *
 * <p>Thread-safety note: one execute()/resume() call at a time per instance.
 * {@link #pause()} may be called from any thread while execute/resume is in progress.
 */
@Slf4j
public class RegnexeAgent {

    private final FlowEngine flowEngine;
    private final ChainActor chainActor;
    private final CapabilitySearcher capabilitySearcher;
    private final TaskPlanner taskPlanner;
    private final CapabilityExecutor capabilityExecutor;
    private final Reflector reflector;
    private final ModelProvider llmProvider;
    private final ModelSpec defaultModel;
    /** Nullable — TaskPlanner falls back to defaultModel when unset. */
    private final ModelSpec plannerModel;
    /** Nullable — Reflector falls back to defaultModel when unset. */
    private final ModelSpec reflectorModel;
    private final Marketplace marketplace;
    private final TaskStore taskStore;
    private final ResultComposer resultComposer;
    private final int maxRounds;
    private final AgentEventListener eventListener;
    private final ConversationStorage sessionStorage;
    private final int sessionBufferSize;
    private final int sessionCompactPeriod;
    private final AgentContext agentContext;
    private final int maxAgentIterations;
    private final int maxConsecutiveToolFailures;
    private final int maxContextOutputChars;
    private final boolean verbose;
    private final ConversationMemory sessionMemory;
    private final Path claudeCompatWorkspace;
    private final String projectMemory;
    private final java.util.Set<String> baseToolNames;

    /** Set at the start of each execute()/resume(); checked by pause(). */
    private volatile AtomicBoolean activeStopSignal;

    RegnexeAgent(FlowEngine flowEngine,
                ChainActor chainActor,
                CapabilitySearcher capabilitySearcher,
                TaskPlanner taskPlanner,
                CapabilityExecutor capabilityExecutor,
                Reflector reflector,
                ModelProvider llmProvider,
                ModelSpec defaultModel,
                ModelSpec plannerModel,
                ModelSpec reflectorModel,
                Marketplace marketplace,
                TaskStore taskStore,
                ResultComposer resultComposer,
                int maxRounds,
                AgentEventListener eventListener,
                ConversationStorage sessionStorage,
                int sessionBufferSize,
                int sessionCompactPeriod,
                AgentContext agentContext,
                int maxAgentIterations,
                int maxConsecutiveToolFailures,
                int maxContextOutputChars,
                boolean verbose,
                ConversationMemory sessionMemory,
                Path claudeCompatWorkspace,
                String projectMemory,
                java.util.Set<String> baseToolNames) {
        this.flowEngine = flowEngine;
        this.chainActor = chainActor;
        this.capabilitySearcher = capabilitySearcher;
        this.taskPlanner = taskPlanner;
        this.capabilityExecutor = capabilityExecutor;
        this.reflector = reflector;
        this.llmProvider = llmProvider;
        this.defaultModel = defaultModel;
        this.plannerModel = plannerModel;
        this.reflectorModel = reflectorModel;
        this.marketplace = marketplace;
        this.taskStore = taskStore;
        this.resultComposer = resultComposer;
        this.maxRounds = maxRounds;
        this.eventListener = eventListener;
        this.sessionStorage = sessionStorage;
        this.sessionBufferSize = sessionBufferSize;
        this.sessionCompactPeriod = sessionCompactPeriod;
        this.agentContext = agentContext;
        this.maxAgentIterations = maxAgentIterations;
        this.maxConsecutiveToolFailures = maxConsecutiveToolFailures;
        this.maxContextOutputChars = maxContextOutputChars;
        this.verbose = verbose;
        this.sessionMemory = sessionMemory;
        this.claudeCompatWorkspace = claudeCompatWorkspace;
        this.projectMemory = projectMemory;
        this.baseToolNames = baseToolNames != null ? baseToolNames : java.util.Set.of();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public AgentResult execute(String goal) {
        TaskRequest request = new TaskRequest();
        request.setGoal(goal);
        return execute(request);
    }

    public AgentResult execute(TaskRequest request) {
        TaskExecutionState state = initState(request);
        taskStore.save(state);

        eventListener.dispatch(AgentEvent.of(state.getTaskId(), 0, EventType.AGENT_STARTED,
                "Goal: " + request.getGoal() + " | maxRounds: " + maxRounds));

        List<HistoryInfos> sessionHistory = loadSessionHistory(state.getSessionId());
        return runLoop(state, sessionHistory, false);
    }

    /** Equivalent to {@link #resume(String, String, boolean)} with {@code force=false}. */
    public AgentResult resume(String sessionId, String supplementInput) {
        return resume(sessionId, supplementInput, false);
    }

    /**
     * Resume the most recently paused (or, with {@code force}, FAILED) task for the given
     * session. The supplement input is appended to the original request so Planner can see both
     * the original goal and the new context in separate prompt sections.
     *
     * @param force also consider a FAILED task resumable — see {@code TaskStore.listResumable}'s
     *              javadoc for when this is appropriate (the underlying cause has been fixed
     *              since, e.g. a billing/vendor-config change) vs. not (a real bug — retrying
     *              blindly just burns the same error again).
     */
    public AgentResult resume(String sessionId, String supplementInput, boolean force) {
        List<TaskExecutionState> resumable = taskStore.listResumable(sessionId, force);
        if (resumable.isEmpty()) {
            throw new IllegalStateException("No resumable task found for session: " + sessionId);
        }
        TaskExecutionState state = resumable.stream()
                .max(Comparator.comparingLong(TaskExecutionState::getUpdatedAt))
                .orElseThrow();

        state.setStatus(TaskStatus.RUNNING);
        if (supplementInput != null && !supplementInput.isBlank()) {
            state.getRequest().setSupplementInput(supplementInput);
        }

        eventListener.dispatch(AgentEvent.of(state.getTaskId(), state.getCurrentRound(),
                EventType.AGENT_STARTED,
                "Resuming | rounds done: " + state.getCurrentRound()
                + (supplementInput != null ? " | supplement: " + supplementInput : "")));

        List<HistoryInfos> sessionHistory = loadSessionHistory(state.getSessionId());
        return runLoop(state, sessionHistory, true);
    }

    /**
     * Signal the currently-running McpAgentExecutor to stop.
     * The task transitions to PAUSED and can be resumed via {@link #resume}.
     * Safe to call from any thread.
     */
    public void pause() {
        AtomicBoolean signal = this.activeStopSignal;
        if (signal != null) {
            signal.set(true);
        }
    }

    /** Read-only access to the marketplace, e.g. for a CLI to list/resolve capabilities by name. */
    public Marketplace getMarketplace() {
        return marketplace;
    }

    /**
     * Directly invoke a SKILL capability by id, bypassing Search/Plan/Reflect.
     * Used for explicit user-triggered invocation (e.g. CLI "/skill-name args"), as opposed to
     * the planner autonomously selecting the skill during {@link #execute}.
     *
     * <p>Does not create a {@link TaskExecutionState} or touch {@code taskStore} — a skill run
     * cannot be paused/resumed via {@link #pause()}/{@link #resume}. Events still flow through
     * the same {@link #eventListener} used by execute()/resume(), so a caller's event rendering
     * (including the {@code TASK_TOKEN_SUMMARY} that {@code TokenAggregatingEventListener} emits
     * before {@code AGENT_COMPLETED}) needs no special-casing.
     *
     * @param capabilityId capability id as registered in the marketplace (format:
     *                      {@code <pluginId>.<skillName>})
     * @param args          raw argument text passed to the skill's internal executor as the user turn
     * @param sessionId     session to store the resulting turn under; null skips session-history storage
     * @param displayGoal   human-readable form for session history (e.g. "/review src/Foo.java");
     *                      falls back to "/<capabilityId> <args>" when null/blank
     */
    public AgentResult executeSkill(String capabilityId, String args, String sessionId, String displayGoal) {
        CapabilityDescriptor cap = marketplace.resolveDescriptor(capabilityId);
        if (cap == null) {
            throw new IllegalArgumentException("Unknown skill: " + capabilityId);
        }
        if (cap.getType() != CapabilityType.SKILL || cap.getSkillConfig() == null) {
            throw new IllegalArgumentException("Not a skill capability: " + capabilityId);
        }

        String taskId = UUID.randomUUID().toString();
        BaseChatModel llm = llmProvider.provide(defaultModel);

        eventListener.dispatch(AgentEvent.of(taskId, 0, EventType.AGENT_STARTED,
                "Skill: " + capabilityId + " | args: " + args));

        Skill.Builder skillBuilder = Skill.from(cap.getSkillConfig(), chainActor).llm(llm);
        // executeSkill() otherwise falls back to j-langchain Skill.Builder's own
        // DEFAULT_MAX_ITERATIONS (10) — a hardcoded budget the CLI's agent.max_agent_iterations
        // config has never reached. Confirmed for real: robot-article-writing's actual workflow
        // (query schema, read news-sources.md, cross-check existing articles, research, draft,
        // upload images, dry-run, commit) burned all 10 just on setup/orientation and got cut
        // off with "Max iterations (10) reached" before doing any real work. Only apply our
        // configured budget when the skill's own SKILL.md hasn't deliberately declared a
        // tighter/looser one — respect explicit skill-author intent over the CLI default.
        if (cap.getSkillConfig().getMaxIterations() == null) {
            skillBuilder.maxIterations(maxAgentIterations);
        }
        // Resolve the same baseToolNames the Planner-driven path (CapabilityExecutor's SKILL
        // branch) unconditionally grants every selected skill — see that class's javadoc: "a
        // Skill shares the main agent's full tool access because it runs in the same context".
        // Without this, /skill-name direct invocation was the ONE path that did NOT share it:
        // Skill.from(...) builds a standalone Skill with no parentTools injected, so a skill
        // whose job requires touching real project files (not just its own .rex/ tree) had no
        // way to reach them — confirmed for real against robot-article-writing, whose lib/db.py
        // and lib/upload.py live at the project root, one level above the claudeCompatWorkspace
        // sandbox: file_exists/list_directory calls for anything outside it threw
        // "SecurityException: Path escapes workspace", and the LLM had no fallback but to
        // (incorrectly) write article payloads into the sandbox instead of the real DB.
        List<Tool> baseTools = baseToolNames.stream()
                .map(marketplace::resolveDescriptor)
                .filter(java.util.Objects::nonNull)
                .map(CapabilityDescriptor::getTool)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (!baseTools.isEmpty()) {
            // Real tool access supersedes the claudeCompatMode sandbox for this skill: leaving
            // both active would register two tools named "bash"/"read_file" (SkillWorkspaceTools
            // uses the same names as FileTools/BashTool), which is at best redundant and at worst
            // lets the model reach for the sandboxed one when the real one was intended.
            skillBuilder.claudeCompatMode(false);
        } else if (claudeCompatWorkspace != null) {
            skillBuilder.claudeCompatWorkspace(claudeCompatWorkspace);
        }
        if (verbose) {
            skillBuilder.verbose(true);
        } else {
            String scope = "[skill:" + cap.getName() + "]";
            skillBuilder.onLlm(text -> eventListener.dispatch(
                    AgentEvent.of(taskId, 0, EventType.SKILL_LLM_RESPONDED, scope + " " + text)));
            skillBuilder.onToolCall(tc -> eventListener.dispatch(
                    AgentEvent.of(taskId, 0, EventType.TOOL_CALLED, scope + " " + tc)));
            skillBuilder.onObservation(obs -> eventListener.dispatch(
                    AgentEvent.of(taskId, 0, EventType.TOOL_RESULT, scope + " " + obs)));
        }
        skillBuilder.onTokenUsage(u -> eventListener.dispatch(
                AgentEvent.ofCapabilityTokenUsage(taskId, 0, cap.getName(), u)));

        String finalText;
        TaskStatus status;
        try {
            Skill skill = skillBuilder.build();
            if (!baseTools.isEmpty()) {
                skill.injectParentTools(baseTools);
            }
            finalText = skill.invoke(args == null ? "" : args);
            status = TaskStatus.FINISHED;
        } catch (Exception e) {
            log.warn("executeSkill '{}' failed: {}", capabilityId, e.getMessage());
            finalText = "Skill execution failed: " + e.getMessage();
            status = TaskStatus.FAILED;
        }

        if (sessionId != null && status == TaskStatus.FINISHED) {
            String humanTurn = (displayGoal != null && !displayGoal.isBlank())
                    ? displayGoal : ("/" + capabilityId + " " + (args == null ? "" : args)).trim();
            storeSessionRound(sessionId, humanTurn, finalText);
        }

        eventListener.dispatch(AgentEvent.of(taskId, 0, EventType.AGENT_COMPLETED,
                "Status: " + status + " | Skill: " + capabilityId));

        return AgentResult.builder()
                .taskId(taskId)
                .status(status)
                .finalText(finalText)
                .build();
    }

    // ── Loop ─────────────────────────────────────────────────────────────────

    private AgentResult runLoop(TaskExecutionState state, List<HistoryInfos> sessionHistory, boolean resumeMode) {
        AtomicBoolean stopSignal = new AtomicBoolean(false);
        this.activeStopSignal = stopSignal;

        Map<String, Object> transmitMap = buildTransmitMap(state, stopSignal, sessionHistory, resumeMode);

        // Loop condition uses state.getCurrentRound() so resume continues correctly
        // from wherever the prior execution left off.
        FlowInstance flowInstance = flowEngine.builder()
                .loop(
                        i -> state.getStatus() == TaskStatus.RUNNING
                                && state.getCurrentRound() < state.getMaxRounds(),
                        capabilitySearcher, taskPlanner, capabilityExecutor, reflector
                )
                .build();

        // Set only on the two PAUSED-by-exception paths below, dispatched as LOOP_PAUSE_REASON
        // further down so a host can explain *why* it paused instead of just "paused" — a raw
        // HTTP failure and a Ctrl+C are both PAUSED but not equally self-explanatory.
        String pauseReason = null;

        try {
            flowEngine.execute(flowInstance, state.getRequest(), transmitMap);
        } catch (Exception e) {
            Integer httpCode = findHttpStatusCode(e);
            if (isTransientIOException(e) || (httpCode != null && RETRYABLE_HTTP_CODES.contains(httpCode))) {
                // A network blip (SocketTimeoutException) or a vendor-side hiccup the vendor
                // itself says is worth retrying (429 rate limit, 500/502/503) shouldn't strand
                // already-completed rounds in a dead FAILED state. PAUSED keeps the task eligible
                // for listResumable()/--resume so real work already done isn't lost.
                pauseReason = httpCode != null
                        ? "vendor returned HTTP " + httpCode + " (likely transient — rate limit or a server-side hiccup); safe to just --resume shortly"
                        : "a transient I/O error (" + e.getMessage() + ")";
                log.warn("RegnexeAgent loop paused: {}", pauseReason);
                state.setStatus(TaskStatus.PAUSED);
                taskStore.save(state);
            } else if (httpCode != null && USER_ACTIONABLE_HTTP_CODES.contains(httpCode)) {
                // 401/402: the vendor rejected the request over something only the user can fix
                // outside this process (top up billing, replace an expired/invalid key) — not a
                // code bug, and not something retrying on its own will ever get past either. Still
                // PAUSED, not FAILED: once the real cause is fixed, a plain --resume should just
                // work, same as any other pause — no need to reach for --force-resume for this
                // class of error specifically.
                pauseReason = "vendor rejected the request (HTTP " + httpCode + "): " + e.getMessage()
                        + " — fix this (billing/API key), then --resume";
                log.warn("RegnexeAgent loop paused: {}", pauseReason);
                state.setStatus(TaskStatus.PAUSED);
                taskStore.save(state);
            } else {
                // Includes 403/404 (a real config mistake — wrong model name, no permission —
                // retrying the identical request will just fail the identical way) and anything
                // else unclassified: stays a hard FAILED, not silently retried.
                log.error("RegnexeAgent loop failed: {}", e.getMessage(), e);
                state.setStatus(TaskStatus.FAILED);
                taskStore.save(state);
                throw e;
            }
        }

        if (pauseReason != null) {
            // The one event dedicated to this — see EventType.LOOP_PAUSE_REASON's javadoc for why
            // AGENT_COMPLETED (below, fired unconditionally) isn't itself rendered by the CLI.
            eventListener.dispatch(AgentEvent.of(state.getTaskId(), state.getCurrentRound(),
                    EventType.LOOP_PAUSE_REASON, pauseReason));
        }

        if (state.getStatus() == TaskStatus.RUNNING) {
            state.setStatus(TaskStatus.TIMEOUT);
            taskStore.save(state);
        }

        this.activeStopSignal = null;

        List<RoundRecord> rounds = state.getRounds();
        ReflectionDecision lastDecision = (rounds == null || rounds.isEmpty())
                ? null
                : rounds.get(rounds.size() - 1).getReflection();
        String finalText = resultComposer.compose(state, lastDecision);

        if (state.getStatus() == TaskStatus.FINISHED || state.getStatus() == TaskStatus.ESCALATED) {
            String displayGoal = state.getRequest().getDisplayGoal();
            String humanTurn = (displayGoal != null && !displayGoal.isBlank())
                    ? displayGoal : state.getRequest().getGoal();
            storeSessionRound(state.getSessionId(), humanTurn, finalText);
        }

        eventListener.dispatch(AgentEvent.of(state.getTaskId(), state.getCurrentRound(),
                EventType.AGENT_COMPLETED,
                "Status: " + state.getStatus() + " | Rounds: " + state.getCurrentRound()
                        + (pauseReason != null ? " | Reason: " + pauseReason : "")));

        return AgentResult.builder()
                .taskId(state.getTaskId())
                .status(state.getStatus())
                .finalText(finalText)
                .state(state)
                .build();
    }

    /** True if an IOException (e.g. SocketTimeoutException from an LLM call) appears anywhere in the cause chain. */
    private boolean isTransientIOException(Throwable e) {
        for (Throwable cur = e; cur != null; cur = cur.getCause()) {
            if (cur instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    // HTTP status codes worth treating like a transient I/O error — a 429 rate limit or a
    // 500/502/503 vendor-side hiccup isn't a code bug, and the vendor itself is effectively saying
    // "this will probably work if you just try again."
    private static final java.util.Set<Integer> RETRYABLE_HTTP_CODES = java.util.Set.of(429, 500, 502, 503);
    // HTTP status codes that mean the vendor rejected the request over something only the user can
    // fix outside this process — insufficient balance, an invalid/expired key. Not transient (won't
    // resolve itself), but also not a code bug (403/404, by contrast, mean the request itself is
    // wrong — a bad model name or missing permission — and stay a hard FAILED below).
    private static final java.util.Set<Integer> USER_ACTIONABLE_HTTP_CODES = java.util.Set.of(401, 402);

    /**
     * Walks the cause chain for an {@link org.salt.jlangchain.ai.client.AiException} (thrown by
     * j-langchain's HTTP clients for any non-2xx LLM API response — see
     * {@code HttpStreamClient.failureException()}) and returns its status code, or null if none
     * is present anywhere in the chain (e.g. a plain IOException, or an exception from somewhere
     * that isn't an HTTP call at all).
     */
    private Integer findHttpStatusCode(Throwable e) {
        for (Throwable cur = e; cur != null; cur = cur.getCause()) {
            if (cur instanceof org.salt.jlangchain.ai.client.AiException ai) {
                return ai.getCode();
            }
        }
        return null;
    }

    private Map<String, Object> buildTransmitMap(TaskExecutionState state,
                                                  AtomicBoolean stopSignal,
                                                  List<HistoryInfos> sessionHistory,
                                                  boolean resumeMode) {
        Map<String, Object> map = new HashMap<>();
        map.put(ContextBusKeys.STATE, state);
        map.put(ContextBusKeys.CHAIN_ACTOR, chainActor);
        map.put(ContextBusKeys.EVENT_LISTENER, eventListener);
        map.put(ContextBusKeys.STOP_SIGNAL, stopSignal);
        map.put(ContextBusKeys.TASK_STORE, taskStore);
        map.put(ContextBusKeys.AGENT_CONTEXT, agentContext);
        if (llmProvider != null) {
            map.put(ContextBusKeys.LLM_PROVIDER, llmProvider);
        }
        if (defaultModel != null) {
            map.put(ContextBusKeys.DEFAULT_MODEL, defaultModel);
            if (plannerModel != null) map.put(ContextBusKeys.PLANNER_MODEL, plannerModel);
            if (reflectorModel != null) map.put(ContextBusKeys.REFLECTOR_MODEL, reflectorModel);
        }
        if (marketplace != null) {
            map.put(ContextBusKeys.MARKETPLACE, marketplace);
        }
        if (sessionHistory != null && !sessionHistory.isEmpty()) {
            map.put(ContextBusKeys.SESSION_HISTORY, sessionHistory);
        }
        map.put(ContextBusKeys.RESUME_MODE, resumeMode);
        map.put(ContextBusKeys.MAX_AGENT_ITERATIONS, maxAgentIterations);
        // Untouched baseline for TaskPlanner to compute each round's override against — see its
        // own javadoc for why MAX_AGENT_ITERATIONS itself (mutated per round) isn't safe to reuse.
        map.put(ContextBusKeys.MAX_AGENT_ITERATIONS_DEFAULT, maxAgentIterations);
        map.put(ContextBusKeys.MAX_CONSECUTIVE_TOOL_FAILURES, maxConsecutiveToolFailures);
        map.put(ContextBusKeys.MAX_CONTEXT_OUTPUT_CHARS, maxContextOutputChars);
        map.put(ContextBusKeys.VERBOSE, verbose);
        if (claudeCompatWorkspace != null) {
            map.put(ContextBusKeys.CLAUDE_COMPAT_WORKSPACE, claudeCompatWorkspace);
        }
        if (projectMemory != null && !projectMemory.isBlank()) {
            map.put(ContextBusKeys.PROJECT_MEMORY, projectMemory);
        }
        if (!baseToolNames.isEmpty()) {
            map.put(ContextBusKeys.BASE_TOOL_NAMES, baseToolNames);
        }
        return map;
    }

    // ── State init ───────────────────────────────────────────────────────────

    private TaskExecutionState initState(TaskRequest request) {
        TaskExecutionState state = new TaskExecutionState();
        state.setTaskId(UUID.randomUUID().toString());
        state.setSessionId(request.getSessionId() != null
                ? request.getSessionId() : UUID.randomUUID().toString());
        state.setRequest(request);
        state.setMaxRounds(maxRounds);
        state.setCreatedAt(System.currentTimeMillis());
        state.setStatus(TaskStatus.RUNNING);
        state.setCurrentRound(0);
        state.setUpdatedAt(System.currentTimeMillis());
        state.setRounds(new ArrayList<>());
        return state;
    }

    // ── Session memory helpers ───────────────────────────────────────────────

    private List<HistoryInfos> loadSessionHistory(String sessionId) {
        List<HistoryInfos> history;
        if (sessionMemory != null) {
            history = sessionMemory.readHistory();
        } else if (sessionStorage != null) {
            long longId = (long) sessionId.hashCode();
            history = PeriodicConversationSummaryMemoryReader.builder()
                    .appId(0L).userId(0L).sessionId(longId).storage(sessionStorage).build()
                    .readHistory();
        } else {
            return null;
        }
        return (history == null || history.isEmpty()) ? null : history;
    }

    private void storeSessionRound(String sessionId, String goal, String answer) {
        if (answer == null || answer.isBlank()) return;
        int sessionCap = Math.max(100, maxContextOutputChars / 2);
        String storedAnswer = answer.length() > sessionCap
                ? compressForSession(answer, sessionCap)
                : answer;
        ConversationMemory memory = sessionMemory;
        if (memory == null) {
            if (sessionStorage == null) return;
            if (defaultModel == null) {
                log.debug("Session storer skipped: no defaultModel configured for summary LLM");
                return;
            }
            long longId = (long) sessionId.hashCode();
            // Periodic (batch) compaction by default: compresses a whole batch of rounds at
            // once when the buffer fills, instead of one round every time it overflows — far
            // fewer summarization LLM calls on long sessions than the old rolling strategy
            // (ConversationSummaryBufferMemory, still available via explicit withSessionMemory).
            memory = PeriodicConversationSummaryMemory.builder()
                    .appId(0L).userId(0L).sessionId(longId)
                    .maxSize(sessionCompactPeriod)
                    .storage(sessionStorage)
                    .llm(llmProvider.provide(defaultModel))
                    .build();
        }
        HistoryInfos turn = HistoryInfos.builder()
                .type(HistoryInfos.Type.NORMAL)
                .messages(List.of(
                        BaseMessage.fromMessage(MessageType.HUMAN.getCode(), goal),
                        BaseMessage.fromMessage(MessageType.AI.getCode(), storedAnswer)
                ))
                .build();
        memory.storeHistory(turn);
    }

    private String compressForSession(String text, int targetChars) {
        BaseChatModel llm = llmProvider.provide(defaultModel);
        return TextCompressor.compress(text, targetChars, chainActor, llm);
    }
}
