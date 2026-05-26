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

package org.salt.regnexe.agent.core.common.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.salt.jlangchain.core.ChainActor;
import org.salt.jlangchain.core.llm.BaseChatModel;
import org.salt.jlangchain.core.parser.StrOutputParser;
import org.salt.jlangchain.core.parser.generation.ChatGeneration;
import org.salt.jlangchain.core.prompt.chat.ChatPromptTemplate;

import java.util.List;
import java.util.Map;

/**
 * Compresses long text using an LLM summary call, with truncation as a fallback.
 */
@Slf4j
public final class TextCompressor {

    private static final String PROMPT =
            "Summarize the following execution result in 1-2 sentences " +
            "(no lists, max ${targetChars} characters). " +
            "Cover: what task was completed, what the key output was, " +
            "and whether it succeeded.\n\n${text}";

    private TextCompressor() {}

    /**
     * Returns {@code text} unchanged if it fits within {@code targetChars}.
     * Otherwise calls the LLM to produce a compact summary; falls back to
     * hard truncation if the LLM call fails.
     */
    public static String compress(String text, int targetChars,
                                  ChainActor chainActor, BaseChatModel llm) {
        if (text == null || text.length() <= targetChars) return text;
        try {
            ChatGeneration result = chainActor.invoke(
                    chainActor.builder()
                            .next(ChatPromptTemplate.fromMessages(List.of(
                                    Pair.of("human", PROMPT))))
                            .next(llm)
                            .next(new StrOutputParser())
                            .build(),
                    Map.of("text", text, "targetChars", String.valueOf(targetChars)));
            String summary = result != null ? result.getText() : null;
            if (summary != null && !summary.isBlank()) {
                log.debug("Text compressed: {} -> {} chars", text.length(), summary.length());
                return summary;
            }
        } catch (Exception e) {
            log.warn("Text compression failed, falling back to truncation: {}", e.getMessage());
        }
        return text.substring(0, targetChars) + "...(truncated)";
    }
}
