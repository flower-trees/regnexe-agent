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

package org.salt.regnexeagent.core.llm;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Well-known vendor identifiers recognized by {@link DefaultModelProvider}.
 * Pass the {@link #value()} string when calling a custom {@link ModelProvider}.
 */
@Getter
@RequiredArgsConstructor
public enum Vendor {

    ALIYUN("aliyun"),
    DEEPSEEK("deepseek"),
    DOUBAO("doubao"),
    HUNYUAN("hunyuan"),
    LINGYI("lingyi"),
    MINIMAX("minimax"),
    MOONSHOT("moonshot"),
    OLLAMA("ollama"),
    OPENAI("openai"),
    QIANFAN("qianfan"),
    STEPFUN("stepfun"),
    ZHIPU("zhipu");

    private final String value;
}
