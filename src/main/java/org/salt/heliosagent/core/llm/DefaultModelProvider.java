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
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.llm.deepseek.ChatDeepseek;
import org.salt.jlangchain.core.llm.doubao.ChatDoubao;
import org.salt.jlangchain.core.llm.hunyuan.ChatHunyuan;
import org.salt.jlangchain.core.llm.lingyi.ChatLingyi;
import org.salt.jlangchain.core.llm.minimax.ChatMinimax;
import org.salt.jlangchain.core.llm.moonshot.ChatMoonshot;
import org.salt.jlangchain.core.llm.ollama.ChatOllama;
import org.salt.jlangchain.core.llm.openai.ChatOpenAI;
import org.salt.jlangchain.core.llm.qianfan.ChatQianfan;
import org.salt.jlangchain.core.llm.stepfun.ChatStepfun;
import org.salt.jlangchain.core.llm.zhipu.ChatZhipu;

/**
 * Prefix-based ModelProvider that covers all vendors bundled in j-langchain.
 *
 * Routing rules (first match wins):
 *   qwen- / qwq-          → ChatAliyun
 *   deepseek-             → ChatDeepseek
 *   ep-                   → ChatDoubao  (endpoint-ID format)
 *   hunyuan-              → ChatHunyuan
 *   yi-                   → ChatLingyi
 *   MiniMax- / minimax-   → ChatMinimax
 *   moonshot-             → ChatMoonshot
 *   *:* (name:tag)        → ChatOllama
 *   gpt- / o1- / o3- / o4- → ChatOpenAI
 *   ernie-                → ChatQianfan
 *   step-                 → ChatStepfun
 *   glm-                  → ChatZhipu
 */
public class DefaultModelProvider implements ModelProvider {

    @Override
    public BaseChatModel provide(String modelName) {
        if (modelName == null) {
            throw new IllegalArgumentException("modelName must not be null");
        }
        String m = modelName.toLowerCase();

        if (m.startsWith("qwen-") || m.startsWith("qwq-")) {
            return ChatAliyun.builder().model(modelName).build();
        }
        if (m.startsWith("deepseek-")) {
            return ChatDeepseek.builder().model(modelName).build();
        }
        if (m.startsWith("ep-")) {
            return ChatDoubao.builder().model(modelName).build();
        }
        if (m.startsWith("hunyuan-")) {
            return ChatHunyuan.builder().model(modelName).build();
        }
        if (m.startsWith("yi-")) {
            return ChatLingyi.builder().model(modelName).build();
        }
        if (m.startsWith("minimax-") || modelName.startsWith("MiniMax-")) {
            return ChatMinimax.builder().model(modelName).build();
        }
        if (m.startsWith("moonshot-")) {
            return ChatMoonshot.builder().model(modelName).build();
        }
        if (modelName.contains(":")) {
            return ChatOllama.builder().model(modelName).build();
        }
        if (m.startsWith("gpt-") || m.startsWith("o1-") || m.startsWith("o3-") || m.startsWith("o4-")) {
            return ChatOpenAI.builder().model(modelName).build();
        }
        if (m.startsWith("ernie-")) {
            return ChatQianfan.builder().model(modelName).build();
        }
        if (m.startsWith("step-")) {
            return ChatStepfun.builder().model(modelName).build();
        }
        if (m.startsWith("glm-")) {
            return ChatZhipu.builder().model(modelName).build();
        }

        throw new IllegalArgumentException("No vendor matched for model: " + modelName);
    }
}
