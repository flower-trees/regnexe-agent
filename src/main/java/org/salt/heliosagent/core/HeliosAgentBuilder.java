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

package org.salt.heliosagent.core;

import lombok.extern.slf4j.Slf4j;
import org.salt.function.flow.FlowEngine;
import org.salt.heliosagent.core.event.AgentEventListener;
import org.salt.heliosagent.core.llm.DefaultModelProvider;
import org.salt.heliosagent.core.llm.ModelProvider;
import org.salt.heliosagent.core.llm.ModelSpec;
import org.salt.heliosagent.core.llm.Vendor;
import org.salt.heliosagent.core.market.Marketplace;
import org.salt.heliosagent.core.task.ResultComposer;
import org.salt.heliosagent.core.task.store.TaskStore;
import org.salt.heliosagent.core.task.worker.CapabilityExecutor;
import org.salt.heliosagent.core.task.worker.CapabilitySearcher;
import org.salt.heliosagent.core.task.worker.Reflector;
import org.salt.heliosagent.core.task.worker.TaskPlanner;
import org.salt.jlangchain.core.ChainActor;
import org.springframework.stereotype.Component;

/**
 * Spring-managed factory for {@link HeliosAgent} instances.
 * Holds j-langchain infrastructure (FlowEngine, ChainActor); callers supply
 * runtime config via the fluent Builder returned by each with*() method.
 *
 * <pre>
 * HeliosAgent agent = heliosAgentBuilder
 *     .withLlmProvider(myProvider)
 *     .withPluginMarket(marketplace)
 *     .build();
 * </pre>
 */
@Slf4j
@Component
public class HeliosAgentBuilder {

    private final FlowEngine flowEngine;
    private final ChainActor chainActor;

    public HeliosAgentBuilder(FlowEngine flowEngine, ChainActor chainActor) {
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

    public Builder withMaxRounds(int maxRounds) {
        return new Builder(flowEngine, chainActor).withMaxRounds(maxRounds);
    }

    public Builder withEventListener(AgentEventListener listener) {
        return new Builder(flowEngine, chainActor).withEventListener(listener);
    }

    // -------------------------------------------------------------------------

    public static class Builder {

        private static final int DEFAULT_MAX_ROUNDS = 10;

        private final FlowEngine flowEngine;
        private final ChainActor chainActor;

        private ModelProvider llmProvider;
        private ModelSpec defaultModel;
        private Marketplace marketplace;
        private TaskStore taskStore;
        private ResultComposer resultComposer;
        private int maxRounds = DEFAULT_MAX_ROUNDS;
        private AgentEventListener eventListener;

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

        public HeliosAgent build() {
            return new HeliosAgent(
                    flowEngine,
                    chainActor,
                    new CapabilitySearcher(),
                    new TaskPlanner(),
                    new CapabilityExecutor(),
                    new Reflector(),
                    llmProvider != null ? llmProvider : new DefaultModelProvider(),
                    defaultModel,
                    marketplace,
                    taskStore,
                    resultComposer,
                    maxRounds,
                    eventListener != null ? eventListener : AgentEventListener.NO_OP
            );
        }
    }
}
