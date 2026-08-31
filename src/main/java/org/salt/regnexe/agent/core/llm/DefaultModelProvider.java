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

package org.salt.regnexe.agent.core.llm;

import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.llm.aliyun.ChatAliyun;
import org.salt.jlangchain.core.llm.custom.ChatCustom;
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
 * Routes a {@link ModelSpec} to the matching j-langchain Chat class.
 *
 * When {@link ModelSpec#getVendor()} is set, vendor takes priority (explicit routing).
 * Otherwise falls back to model-name prefix matching:
 *
 *   qwen- / qwq-              → aliyun
 *   deepseek-                 → deepseek
 *   ep-                       → doubao  (endpoint-ID format)
 *   hunyuan-                  → hunyuan
 *   yi-                       → lingyi
 *   MiniMax- / minimax-       → minimax
 *   moonshot-                 → moonshot
 *   name:tag (contains ':')   → ollama
 *   gpt- / o1- / o3- / o4-   → openai
 *   ernie-                    → qianfan
 *   step-                     → stepfun
 *   glm-                      → zhipu
 *
 * "custom" (any OpenAI-Chat-Completions-compatible endpoint not covered above — OpenRouter,
 * Together, Groq, a self-hosted server, a corporate gateway) has no prefix rule and must be
 * routed explicitly via {@code vendor: custom}: there is no model-name pattern that reliably
 * signals "this is some arbitrary compatible endpoint", so guessing it from the model string
 * would risk misrouting other vendors' models that happen to contain a similar substring.
 */
public class DefaultModelProvider implements ModelProvider {

    @Override
    public BaseChatModel provide(ModelSpec spec) {
        if (spec == null || spec.getModel() == null) {
            throw new IllegalArgumentException("ModelSpec and model must not be null");
        }
        if (spec.getVendor() != null) {
            return byVendor(spec.getVendor(), spec.getModel());
        }
        return byPrefix(spec.getModel());
    }

    private BaseChatModel byVendor(String vendor, String model) {
        return switch (vendor.toLowerCase()) {
            case "aliyun"   -> ChatAliyun.builder().model(model).build();
            case "custom"   -> ChatCustom.builder().model(model).build();
            case "deepseek" -> ChatDeepseek.builder().model(model).build();
            case "doubao"   -> ChatDoubao.builder().model(model).build();
            case "hunyuan"  -> ChatHunyuan.builder().model(model).build();
            case "lingyi"   -> ChatLingyi.builder().model(model).build();
            case "minimax"  -> ChatMinimax.builder().model(model).build();
            case "moonshot" -> ChatMoonshot.builder().model(model).build();
            case "ollama"   -> ChatOllama.builder().model(model).build();
            case "openai"   -> ChatOpenAI.builder().model(model).build();
            case "qianfan"  -> ChatQianfan.builder().model(model).build();
            case "stepfun"  -> ChatStepfun.builder().model(model).build();
            case "zhipu"    -> ChatZhipu.builder().model(model).build();
            default -> throw new IllegalArgumentException("Unknown vendor: " + vendor);
        };
    }

    private BaseChatModel byPrefix(String model) {
        String m = model.toLowerCase();
        if (m.startsWith("qwen-") || m.startsWith("qwq-")) {
            return ChatAliyun.builder().model(model).build();
        }
        if (m.startsWith("deepseek-")) {
            return ChatDeepseek.builder().model(model).build();
        }
        if (m.startsWith("ep-")) {
            return ChatDoubao.builder().model(model).build();
        }
        if (m.startsWith("hunyuan-")) {
            return ChatHunyuan.builder().model(model).build();
        }
        if (m.startsWith("yi-")) {
            return ChatLingyi.builder().model(model).build();
        }
        if (m.startsWith("minimax-") || model.startsWith("MiniMax-")) {
            return ChatMinimax.builder().model(model).build();
        }
        if (m.startsWith("moonshot-")) {
            return ChatMoonshot.builder().model(model).build();
        }
        if (model.contains(":")) {
            return ChatOllama.builder().model(model).build();
        }
        if (m.startsWith("gpt-") || m.startsWith("o1-") || m.startsWith("o3-") || m.startsWith("o4-")) {
            return ChatOpenAI.builder().model(model).build();
        }
        if (m.startsWith("ernie-")) {
            return ChatQianfan.builder().model(model).build();
        }
        if (m.startsWith("step-")) {
            return ChatStepfun.builder().model(model).build();
        }
        if (m.startsWith("glm-")) {
            return ChatZhipu.builder().model(model).build();
        }
        throw new IllegalArgumentException("No vendor matched for model: " + model);
    }
}
