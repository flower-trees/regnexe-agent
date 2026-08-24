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

package org.salt.regnexe.agent.core.marketplace.scope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges each scope's {@code enabled.yml} declarations into one final enabled/disabled
 * decision per plugin id.
 *
 * <p>Priority is deliberately <b>not</b> hardcoded here — callers supply {@link ScopedEnabledState}
 * layers already ordered from lowest to highest priority (a later layer's key overrides an
 * earlier layer's same key). regnexe-agent has no opinion on {@code .rex} on-disk conventions or
 * which scope should win; that's a harness (regnexe-cli) wiring decision. The final Project-vs-User
 * ordering — and whether a LOCAL layer exists at all — is still an open question, so this class
 * stays agnostic rather than guessing.
 *
 * <p>Only the enabled-state merge problem needs a resolver at all: regnexe's capabilityId is
 * already namespaced by pluginId ({@code pluginId + "." + name}), and
 * {@link org.salt.regnexe.agent.core.marketplace.SimpleMarketplace#install} rejects a duplicate
 * capabilityId outright rather than picking a winner — so there's no separate "same-name
 * capability across scopes" conflict model to encode here (unlike Claude Code's Personal &gt;
 * Project &gt; Bundled skill-name rule).
 */
public class ScopeResolver {

    /** Merges scoped layers (lowest priority first) into one {@code globalId -> enabled} map. */
    public Map<String, Boolean> resolve(List<ScopedEnabledState> layers) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (ScopedEnabledState layer : layers) {
            if (layer.enabled() != null) {
                result.putAll(layer.enabled());
            }
        }
        return result;
    }

    /**
     * Whether {@code globalId} is enabled per the resolved map. A plugin present on disk but
     * never mentioned in any {@code enabled.yml} defaults to enabled — declaring nothing means
     * "no opinion", not "off".
     */
    public boolean isEnabled(Map<String, Boolean> resolved, String globalId) {
        return resolved.getOrDefault(globalId, true);
    }
}
