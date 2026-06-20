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
import org.salt.regnexe.agent.core.market.DefaultPluginManager;
import org.salt.regnexe.agent.core.market.Marketplace;
import org.salt.regnexe.agent.core.market.SimpleMarketplace;
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
import org.springframework.stereotype.Component;

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

    /** Convenience: scan packages for {@code @Plugin} classes without constructing a marketplace manually. */
    public Builder withScanPackages(String... basePackages) {
        return new Builder(flowEngine, chainActor).withScanPackages(basePackages);
    }

    /** Convenience: load plugins from file-system directories without constructing a marketplace manually. */
    public Builder withDirectory(String... dirs) {
        return new Builder(flowEngine, chainActor).withDirectory(dirs);
    }

    // -------------------------------------------------------------------------

    public static class Builder {

        private static final int DEFAULT_MAX_ROUNDS = 10;

        private final FlowEngine flowEngine;
        private final ChainActor chainActor;

        private ModelProvider llmProvider;
        private BaseChatModel directLlm;
        private ModelSpec defaultModel;
        private Marketplace marketplace;
        private TaskStore taskStore;
        private ResultComposer resultComposer;
        private int maxRounds = DEFAULT_MAX_ROUNDS;
        private AgentEventListener eventListener;
        private ConversationStorage sessionStorage;
        private int sessionBufferSize = 10;
        private AgentContext agentContext;
        private int maxAgentIterations = 20;
        private int maxContextOutputChars = 2000;
        private boolean verbose = false;
        private ConversationMemory sessionMemory;

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

        public Builder withAgentContext(AgentContext context) {
            this.agentContext = context;
            return this;
        }

        public Builder withMaxAgentIterations(int maxIterations) {
            this.maxAgentIterations = maxIterations;
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

        /** Convenience: load plugins from file-system directories without constructing a marketplace manually. */
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
                    resolvedMarketplace,
                    taskStore != null ? taskStore : new InMemoryTaskStore(),
                    resultComposer != null ? resultComposer : new DefaultResultComposer(),
                    maxRounds,
                    new TokenAggregatingEventListener(eventListener != null ? eventListener : AgentEventListener.NO_OP),
                    sessionStorage != null ? sessionStorage : new InMemoryConversationStorage(),
                    sessionBufferSize,
                    agentContext != null ? agentContext : FullContext.build(),
                    maxAgentIterations,
                    maxContextOutputChars,
                    verbose,
                    sessionMemory
            );
        }
    }
}
