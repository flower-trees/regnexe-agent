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

package org.salt.regnexe.agent.core.config;

import org.salt.regnexe.agent.core.task.DefaultResultComposer;
import org.salt.regnexe.agent.core.task.ResultComposer;
import org.salt.regnexe.agent.core.task.store.InMemoryTaskStore;
import org.salt.regnexe.agent.core.task.store.TaskStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration for RegnexeAgent.
 * Enables component scanning of the core package (picks up RegnexeAgentBuilder)
 * and registers fallback beans for TaskStore and ResultComposer.
 * Override either bean in your own @Configuration to replace the defaults.
 */
@Configuration
@ComponentScan("org.salt.regnexe.agent.core")
public class RegnexeAgentConfig {

    @Bean
    @ConditionalOnMissingBean
    public TaskStore taskStore() {
        return new InMemoryTaskStore();
    }

    @Bean
    @ConditionalOnMissingBean
    public ResultComposer resultComposer() {
        return new DefaultResultComposer();
    }
}
