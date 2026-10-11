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

package org.salt.regnexe.agent.core.event;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Ids for {@link AgentEvent#getSpanId()}, in OpenTelemetry's format (16 hex chars per span,
 * 32 per trace) so a listener can forward them to an OTLP backend unchanged.
 *
 * <p>A task's trace id and root span id are derived from its task id rather than random: a
 * resumed task — possibly in a different process — must be able to compute them again to attach
 * itself under the original run.
 */
public final class SpanIds {

    private static final SecureRandom RANDOM = new SecureRandom();

    private SpanIds() {
    }

    public static String next() {
        byte[] bytes = new byte[8];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String traceId(String taskId) {
        return digest(String.valueOf(taskId)).substring(0, 32);
    }

    public static String rootSpanId(String taskId) {
        return digest("root:" + taskId).substring(0, 16);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
