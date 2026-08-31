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
import org.salt.regnexe.agent.core.event.AgentEventListener;
import org.salt.regnexe.agent.core.event.TokenAggregatingEventListener;
import org.salt.regnexe.agent.core.llm.DefaultModelProvider;
import org.salt.regnexe.agent.core.llm.ModelProvider;
import org.salt.regnexe.agent.core.llm.ModelSpec;
import org.salt.regnexe.agent.core.llm.Vendor;
import org.salt.regnexe.agent.core.marketplace.loader.DefaultPluginManager;
import org.salt.regnexe.agent.core.marketplace.Marketplace;
import org.salt.regnexe.agent.core.marketplace.SimpleMarketplace;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginDescriptor;
import org.salt.regnexe.agent.core.marketplace.scope.EnabledStateLoader;
import org.salt.regnexe.agent.core.marketplace.scope.Scope;
import org.salt.regnexe.agent.core.marketplace.scope.ScopeResolver;
import org.salt.regnexe.agent.core.marketplace.scope.ScopedEnabledState;
import org.salt.regnexe.agent.core.task.DefaultResultComposer;
import org.salt.regnexe.agent.core.task.ResultComposer;
import org.salt.regnexe.agent.core.task.store.InMemoryTaskStore;
import org.salt.regnexe.agent.core.task.store.TaskStore;
import org.salt.regnexe.agent.core.task.worker.CapabilityExecutor;
import org.salt.regnexe.agent.core.task.worker.CapabilitySearcher;
import org.salt.regnexe.agent.core.task.worker.Reflector;
import org.salt.regnexe.agent.core.task.worker.TaskPlanner;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.agent.memory.AgentContext;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.agent.memory.FullContext;
import org.salt.jlangchain.core.history.memory.ConversationMemory;
import org.salt.jlangchain.core.history.storage.ConversationStorage;
import org.salt.jlangchain.core.history.storage.InMemoryConversationStorage;
import org.salt.jlangchain.core.skill.SkillConfig;
import org.salt.jlangchain.core.subagent.SubAgentConfig;
import org.salt.jlangchain.rag.tools.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Spring-managed factory for {@link RegnexeAgent} instances.
 * Holds j-langchain infrastructure (FlowEngine, ChainActor); callers supply
 * runtime config via the fluent Builder returned by each with*() method.
 *
 * <pre>
 * RegnexeAgent agent = regnexeAgentBuilder
 *     .withLlmProvider(myProvider)
 *     .withPluginMarket(marketplace)
 *     .build();
 * </pre>
 */
@Slf4j
@Component
public class RegnexeAgentBuilder {

    private final FlowEngine flowEngine;
    private final ChainActor chainActor;

    public RegnexeAgentBuilder(FlowEngine flowEngine, ChainActor chainActor) {
        this.flowEngine = flowEngine;
        this.chainActor = chainActor;
    }

    public Builder withLlmProvider(ModelProvider provider) {
        return new Builder(flowEngine, chainActor).withLlmProvider(provider);
    }

    public Builder withPluginMarket(Marketplace marketplace) {
        return new Builder(flowEngine, chainActor).withPluginMarket(marketplace);
    }

    public Builder withTaskStore(TaskStore store) {
        return new Builder(flowEngine, chainActor).withTaskStore(store);
    }

    public Builder withResultComposer(ResultComposer composer) {
        return new Builder(flowEngine, chainActor).withResultComposer(composer);
    }

    public Builder withDefaultModel(String model) {
        return new Builder(flowEngine, chainActor).withDefaultModel(model);
    }

    public Builder withDefaultModel(String vendor, String model) {
        return new Builder(flowEngine, chainActor).withDefaultModel(vendor, model);
    }

    public Builder withDefaultModel(Vendor vendor, String model) {
        return new Builder(flowEngine, chainActor).withDefaultModel(vendor, model);
    }

    public Builder withDefaultModel(BaseChatModel llm) {
        return new Builder(flowEngine, chainActor).withDefaultModel(llm);
    }

    public Builder withMaxRounds(int maxRounds) {
        return new Builder(flowEngine, chainActor).withMaxRounds(maxRounds);
    }

    public Builder withEventListener(AgentEventListener listener) {
        return new Builder(flowEngine, chainActor).withEventListener(listener);
    }

    public Builder withSessionStorage(ConversationStorage storage) {
        return new Builder(flowEngine, chainActor).withSessionStorage(storage);
    }

    public Builder withSessionBufferSize(int maxSize) {
        return new Builder(flowEngine, chainActor).withSessionBufferSize(maxSize);
    }

    /**
     * Trigger threshold for the default session-memory compaction strategy
     * ({@code PeriodicConversationSummaryMemory}) — see {@code Builder#withSessionCompactPeriod}.
     */
    public Builder withSessionCompactPeriod(int period) {
        return new Builder(flowEngine, chainActor).withSessionCompactPeriod(period);
    }

    public Builder withAgentContext(AgentContext context) {
        return new Builder(flowEngine, chainActor).withAgentContext(context);
    }

    public Builder withMaxContextOutputChars(int maxChars) {
        return new Builder(flowEngine, chainActor).withMaxContextOutputChars(maxChars);
    }

    public Builder withSessionMemory(ConversationMemory memory) {
        return new Builder(flowEngine, chainActor).withSessionMemory(memory);
    }

    /** Convenience: register one or more {@code @Plugin} beans without constructing a marketplace manually. */
    public Builder withPlugin(Object... pluginBeans) {
        return new Builder(flowEngine, chainActor).withPlugin(pluginBeans);
    }

    /** Convenience: install one or more pre-built PluginDescriptor objects without constructing a marketplace manually. */
    public Builder withPlugin(PluginDescriptor... descriptors) {
        return new Builder(flowEngine, chainActor).withPlugin(descriptors);
    }

    /** Convenience: scan packages for {@code @Plugin} classes without constructing a marketplace manually. */
    public Builder withScanPackages(String... basePackages) {
        return new Builder(flowEngine, chainActor).withScanPackages(basePackages);
    }

    /** Convenience: load manifest-based plugins from file-system directories without constructing a marketplace manually. */
    public Builder withDirectory(String... dirs) {
        return new Builder(flowEngine, chainActor).withDirectory(dirs);
    }

    /** Convenience: load manifest-less, directly-editable skills (skills/&lt;name&gt;/SKILL.md) without constructing a marketplace manually. */
    public Builder withSkillsDirectory(String... dirs) {
        return new Builder(flowEngine, chainActor).withSkillsDirectory(dirs);
    }

    /**
     * Convenience: load one or more already-resolved plugin directories (each directory itself
     * carries the manifest — e.g. {@code cache/<plugin-id>/<hash>/} paths resolved by
     * {@code PluginCacheInstaller}) without constructing a marketplace manually.
     */
    public Builder withPluginDirectory(String... dirs) {
        return new Builder(flowEngine, chainActor).withPluginDirectory(dirs);
    }

    /** Convenience: register one or more pre-built Tool objects directly as MCP_TOOL capabilities. */
    public Builder withTool(Tool... tools) {
        return new Builder(flowEngine, chainActor).withTool(tools);
    }

    /** Convenience: register a SKILL capability directly from a SkillConfig, without a SKILL.md file. */
    public Builder withSkill(SkillConfig... configs) {
        return new Builder(flowEngine, chainActor).withSkill(configs);
    }

    /** Convenience: register a SUB_AGENT capability directly from a SubAgentConfig, without an AGENT.md file. */
    public Builder withSubAgent(SubAgentConfig... configs) {
        return new Builder(flowEngine, chainActor).withSubAgent(configs);
    }

    // -------------------------------------------------------------------------

    public static class Builder {

        private static final int DEFAULT_MAX_ROUNDS = 10;

        private final FlowEngine flowEngine;
        private final ChainActor chainActor;

        private ModelProvider llmProvider;
        private BaseChatModel directLlm;
        private ModelSpec defaultModel;
        private ModelSpec plannerModel;
        private ModelSpec reflectorModel;
        private Marketplace marketplace;
        private TaskStore taskStore;
        private ResultComposer resultComposer;
        private int maxRounds = DEFAULT_MAX_ROUNDS;
        private AgentEventListener eventListener;
        private ConversationStorage sessionStorage;
        private int sessionBufferSize = 10;
        private int sessionCompactPeriod = 20;
        private AgentContext agentContext;
        private int maxAgentIterations = 20;
        private int maxConsecutiveToolFailures = 5;
        private int maxContextOutputChars = 2000;
        private boolean verbose = false;
        private ConversationMemory sessionMemory;
        private java.nio.file.Path claudeCompatWorkspace;
        private String projectMemory;
        // Names of tools registered via withTool() — see ContextBusKeys.BASE_TOOL_NAMES javadoc.
        // LinkedHashSet: dedupes across repeated/varargs withTool() calls while keeping registration order.
        private final java.util.Set<String> baseToolNames = new java.util.LinkedHashSet<>();

        Builder(FlowEngine flowEngine, ChainActor chainActor) {
            this.flowEngine = flowEngine;
            this.chainActor = chainActor;
        }

        public Builder withLlmProvider(ModelProvider provider) {
            this.llmProvider = provider;
            return this;
        }

        public Builder withDefaultModel(String model) {
            this.defaultModel = ModelSpec.of(model);
            return this;
        }

        public Builder withDefaultModel(String vendor, String model) {
            this.defaultModel = ModelSpec.of(vendor, model);
            return this;
        }

        public Builder withDefaultModel(Vendor vendor, String model) {
            this.defaultModel = ModelSpec.of(vendor, model);
            return this;
        }

        public Builder withDefaultModel(BaseChatModel llm) {
            this.directLlm = llm;
            this.defaultModel = ModelSpec.of("_direct_");
            return this;
        }

        /**
         * Optional per-role override: run TaskPlanner's structured-JSON planning call on a
         * different model than Execute (which stays on {@link #withDefaultModel}). Unset means
         * Planner uses the default model too — see {@code ContextBusKeys.PLANNER_MODEL}'s javadoc
         * for why this specific role is a reasonable place to spend more on a stronger model
         * without the cost scaling the way Execute's would (one small JSON call per round, not a
         * multi-iteration tool-calling loop).
         */
        public Builder withPlannerModel(String vendor, String model) {
            this.plannerModel = ModelSpec.of(vendor, model);
            return this;
        }

        /**
         * Optional per-role override: run Reflector's FINISH/CONTINUE/ESCALATE judgment on a
         * different model than Execute. Unset means Reflector uses the default model too — see
         * {@code ContextBusKeys.REFLECTOR_MODEL}'s javadoc: a wrong FINISH verdict is a one-way
         * door (the task ends; no later round can catch and correct it), unlike a Planner or
         * Execute mistake, so judgment quality here has outsized leverage.
         */
        public Builder withReflectorModel(String vendor, String model) {
            this.reflectorModel = ModelSpec.of(vendor, model);
            return this;
        }

        public Builder withPluginMarket(Marketplace marketplace) {
            this.marketplace = marketplace;
            return this;
        }

        public Builder withTaskStore(TaskStore store) {
            this.taskStore = store;
            return this;
        }

        public Builder withResultComposer(ResultComposer composer) {
            this.resultComposer = composer;
            return this;
        }

        public Builder withMaxRounds(int maxRounds) {
            this.maxRounds = maxRounds;
            return this;
        }

        public Builder withEventListener(AgentEventListener listener) {
            this.eventListener = listener;
            return this;
        }

        public Builder withSessionStorage(ConversationStorage storage) {
            this.sessionStorage = storage;
            return this;
        }

        public Builder withSessionBufferSize(int maxSize) {
            this.sessionBufferSize = maxSize;
            return this;
        }

        /**
         * Trigger threshold for the default session-memory compaction strategy
         * ({@code PeriodicConversationSummaryMemory}, used when no explicit
         * {@link #withSessionMemory} is set) — once the raw-turn buffer reaches this many
         * turns, they're compressed into the rolling summary in one LLM call and the buffer
         * clears. Default 20 — batching the compaction like this means far fewer summarization
         * calls on long sessions than compressing one turn every time the buffer overflows. Has
         * no effect if {@link #withSessionMemory} is set explicitly — that strategy's own trigger
         * rule (if any) takes over.
         */
        public Builder withSessionCompactPeriod(int period) {
            this.sessionCompactPeriod = period;
            return this;
        }

        public Builder withAgentContext(AgentContext context) {
            this.agentContext = context;
            return this;
        }

        public Builder withMaxAgentIterations(int maxIterations) {
            this.maxAgentIterations = maxIterations;
            return this;
        }

        /**
         * Caps how many tool calls in a row are allowed to fail before the executor aborts the
         * round with a diagnostic, instead of grinding through the rest of the {@code
         * maxAgentIterations} budget retrying the same broken thing. Wires j-langchain's
         * {@code McpAgentExecutor.Builder.maxConsecutiveToolFailures} — previously never set by
         * regnexe (defaulted to j-langchain's own {@code 0}, which disables the check entirely).
         * Found via a real Playwright-MCP article-writing test: a genuinely broken external
         * dependency (an admin login endpoint rejecting real credentials) kept getting retried by
         * the model turn after turn until the whole run hit {@code maxAgentIterations} instead of
         * failing fast on that one dependency a few tries in.
         */
        public Builder withMaxConsecutiveToolFailures(int maxConsecutiveToolFailures) {
            this.maxConsecutiveToolFailures = maxConsecutiveToolFailures;
            return this;
        }

        public Builder withMaxContextOutputChars(int maxChars) {
            this.maxContextOutputChars = maxChars;
            return this;
        }

        public Builder withVerbose(boolean verbose) {
            this.verbose = verbose;
            return this;
        }

        /**
         * Root directory for {@link org.salt.jlangchain.core.skill.Skill}'s Claude-compatible
         * mode scoped filesystem tools (see {@code Skill.Builder#claudeCompatWorkspace}),
         * applied to every SKILL capability this agent runs (both planner-selected and
         * {@link RegnexeAgent#executeSkill} direct invocation). Unset means each skill gets its
         * own throwaway temp directory — set this to give skills real, persistent access to
         * (typically) your plugin/skill directory tree, so a skill-authoring skill can see and
         * edit existing skills instead of starting from an empty sandbox every time.
         */
        public Builder withClaudeCompatWorkspace(java.nio.file.Path workspace) {
            this.claudeCompatWorkspace = workspace;
            return this;
        }

        /**
         * Long-term project memory (REX.md-style content) — always present once set, regardless
         * of sessionId, independent of the three memory layers (Session/Task/AgentContext). Read
         * by both {@link TaskPlanner} and {@link CapabilityExecutor}. Typically the caller
         * concatenates a user-scope and a project-scope file before passing it in; this builder
         * doesn't read any files itself.
         */
        public Builder withProjectMemory(String content) {
            this.projectMemory = content;
            return this;
        }

        /**
         * Override the default session memory strategy.
         * The supplied instance must be scoped to a single session (sessionId is
         * fixed at construction time) and must NOT be shared across concurrent
         * agent executions — {@code storeHistory} is a non-atomic read-modify-write.
         */
        public Builder withSessionMemory(ConversationMemory memory) {
            this.sessionMemory = memory;
            return this;
        }

        /** Convenience: register one or more {@code @Plugin} beans without constructing a marketplace manually. */
        public Builder withPlugin(Object... pluginBeans) {
            if (this.marketplace == null) {
                this.marketplace = new SimpleMarketplace();
            }
            DefaultPluginManager mgr = new DefaultPluginManager();
            for (Object bean : pluginBeans) {
                mgr.register(bean);
            }
            this.marketplace.load(mgr);
            return this;
        }

        /** Convenience: install one or more pre-built PluginDescriptor objects without constructing a marketplace manually. */
        public Builder withPlugin(PluginDescriptor... descriptors) {
            if (this.marketplace == null) {
                this.marketplace = new SimpleMarketplace();
            }
            for (PluginDescriptor descriptor : descriptors) {
                this.marketplace.install(descriptor);
            }
            return this;
        }

        /** Convenience: scan packages for {@code @Plugin} classes without constructing a marketplace manually. */
        public Builder withScanPackages(String... basePackages) {
            if (this.marketplace == null) {
                this.marketplace = new SimpleMarketplace();
            }
            DefaultPluginManager mgr = new DefaultPluginManager();
            mgr.scanPackages(basePackages);
            this.marketplace.load(mgr);
            return this;
        }

        /** Convenience: load manifest-based plugins from file-system directories without constructing a marketplace manually. */
        public Builder withDirectory(String... dirs) {
            if (this.marketplace == null) {
                this.marketplace = new SimpleMarketplace();
            }
            DefaultPluginManager mgr = new DefaultPluginManager();
            for (String dir : dirs) {
                mgr.addDirectory(dir);
            }
            this.marketplace.load(mgr);
            return this;
        }

        /** Convenience: load manifest-less, directly-editable skills (skills/&lt;name&gt;/SKILL.md) without constructing a marketplace manually. */
        public Builder withSkillsDirectory(String... dirs) {
            if (this.marketplace == null) {
                this.marketplace = new SimpleMarketplace();
            }
            DefaultPluginManager mgr = new DefaultPluginManager();
            for (String dir : dirs) {
                mgr.addSkillsDirectory(dir);
            }
            this.marketplace.load(mgr);
            return this;
        }

        /**
         * Convenience: load one or more already-resolved plugin directories — each directory
         * itself carries the manifest, unlike {@link #withDirectory} which expects a parent
         * containing many plugin subdirectories.
         */
        public Builder withPluginDirectory(String... dirs) {
            if (this.marketplace == null) {
                this.marketplace = new SimpleMarketplace();
            }
            DefaultPluginManager mgr = new DefaultPluginManager();
            for (String dir : dirs) {
                mgr.addPluginDirectory(dir);
            }
            this.marketplace.load(mgr);
            return this;
        }

        /**
         * Applies each scope's {@code enabled.yml} on top of whatever has been installed so far,
         * lowest-priority layer first (a later layer's key wins — see {@code ScopeResolver}).
         * {@code enabled.yml} keys are {@code <plugin-id>@<marketplace-name>}, but
         * {@link Marketplace#enable}/{@link Marketplace#disable} only key on pluginId — regnexe
         * doesn't namespace its registry by marketplace (a deliberate simplification: two plugins
         * with the same id in different marketplaces are treated as the same plugin), so the
         * {@code @marketplace} suffix is stripped here. A no-op if no marketplace has been
         * populated yet (nothing to enable/disable). {@code enabledYmlByScope} entries with a
         * null path are skipped.
         */
        public Builder withEnabledState(Map<Scope, java.nio.file.Path> enabledYmlByScope, List<Scope> priorityOrder) {
            if (this.marketplace == null) {
                return this;
            }
            EnabledStateLoader loader = new EnabledStateLoader();
            ScopeResolver resolver = new ScopeResolver();
            List<ScopedEnabledState> layers = new ArrayList<>();
            for (Scope scope : priorityOrder) {
                java.nio.file.Path path = enabledYmlByScope.get(scope);
                if (path == null) continue;
                layers.add(new ScopedEnabledState(scope, loader.load(path)));
            }
            Map<String, Boolean> resolved = resolver.resolve(layers);
            resolved.forEach((globalId, isEnabled) -> {
                String pluginId = globalId.contains("@") ? globalId.substring(0, globalId.lastIndexOf('@')) : globalId;
                if (Boolean.TRUE.equals(isEnabled)) {
                    this.marketplace.enable(pluginId);
                } else {
                    this.marketplace.disable(pluginId);
                }
            });
            return this;
        }

        /**
         * Convenience: register one or more pre-built Tool objects directly as MCP_TOOL
         * capabilities. Their names are tracked as "base tools" (see
         * {@code ContextBusKeys.BASE_TOOL_NAMES}) — every selected SKILL capability
         * unconditionally gets access to them, regardless of the skill's own allowedTools
         * declaration or whether the Planner separately selected them this round.
         */
        public Builder withTool(Tool... tools) {
            if (this.marketplace == null) {
                this.marketplace = new SimpleMarketplace();
            }
            DefaultPluginManager mgr = new DefaultPluginManager();
            for (Tool tool : tools) {
                mgr.registerTool(tool);
                this.baseToolNames.add(tool.getName());
            }
            this.marketplace.load(mgr);
            return this;
        }

        /**
         * Convenience: register a SKILL capability directly from a SkillConfig, without a SKILL.md file.
         * capabilityId defaults to {@code config.getName()} — it must be non-blank and unique,
         * since it is shown verbatim to the planner LLM as the selectable capability id.
         */
        public Builder withSkill(SkillConfig... configs) {
            if (this.marketplace == null) {
                this.marketplace = new SimpleMarketplace();
            }
            DefaultPluginManager mgr = new DefaultPluginManager();
            for (SkillConfig config : configs) {
                mgr.registerSkill(config);
            }
            this.marketplace.load(mgr);
            return this;
        }

        /**
         * Convenience: register a SUB_AGENT capability directly from a SubAgentConfig, without an AGENT.md file.
         * capabilityId defaults to {@code config.getName()} — it must be non-blank and unique,
         * since it is shown verbatim to the planner LLM as the selectable capability id.
         */
        public Builder withSubAgent(SubAgentConfig... configs) {
            if (this.marketplace == null) {
                this.marketplace = new SimpleMarketplace();
            }
            DefaultPluginManager mgr = new DefaultPluginManager();
            for (SubAgentConfig config : configs) {
                mgr.registerSubAgent(config);
            }
            this.marketplace.load(mgr);
            return this;
        }

        public RegnexeAgent build() {
            ModelProvider baseProvider = llmProvider != null ? llmProvider : new DefaultModelProvider();
            // withDefaultModel(BaseChatModel) stores a direct LLM for the "_direct_" spec.
            // Wrap the provider so "_direct_" returns that LLM while all other specs (SubAgent
            // custom models) still go through the real provider.
            final BaseChatModel direct = this.directLlm;
            ModelProvider resolvedProvider = direct != null
                    ? spec -> "_direct_".equals(spec.getModel()) ? direct.copy() : baseProvider.provide(spec)
                    : baseProvider;
            Marketplace resolvedMarketplace = marketplace != null ? marketplace : new SimpleMarketplace();

            return new RegnexeAgent(
                    flowEngine,
                    chainActor,
                    new CapabilitySearcher(),
                    new TaskPlanner(),
                    new CapabilityExecutor(),
                    new Reflector(),
                    resolvedProvider,
                    defaultModel,
                    plannerModel,
                    reflectorModel,
                    resolvedMarketplace,
                    taskStore != null ? taskStore : new InMemoryTaskStore(),
                    resultComposer != null ? resultComposer : new DefaultResultComposer(),
                    maxRounds,
                    new TokenAggregatingEventListener(eventListener != null ? eventListener : AgentEventListener.NO_OP),
                    sessionStorage != null ? sessionStorage : new InMemoryConversationStorage(),
                    sessionBufferSize,
                    sessionCompactPeriod,
                    agentContext != null ? agentContext : FullContext.build(),
                    maxAgentIterations,
                    maxConsecutiveToolFailures,
                    maxContextOutputChars,
                    verbose,
                    sessionMemory,
                    claudeCompatWorkspace,
                    projectMemory,
                    baseToolNames
            );
        }
    }
}
