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

package org.salt.heliosagent.core.llm;

import org.salt.jlangchain.core.llm.BaseChatModel;

/**
 * Resolves a model name to a {@link BaseChatModel} instance.
 * Implementations may back this with a static registry, a remote config store,
 * or any dynamic routing strategy.
 */
public interface ModelProvider {

    BaseChatModel provide(String modelName);
}
