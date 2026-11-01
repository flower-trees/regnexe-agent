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

package org.salt.regnexe.agent.core.marketplace.plugin;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Pure data class holding a plugin manifest's declared fields, parsed from either
 * {@code plugin.yaml} (regnexe-native) or {@code .claude-plugin/plugin.json}
 * (Claude Code compat — both are valid YAML, parsed with the same reader).
 *
 * <p>This is the "declaration" layer — what the manifest file says. It is distinct from
 * {@link PluginDescriptor}, which is the normalized, runtime-ready object
 * {@link org.salt.regnexe.agent.core.marketplace.Marketplace#install} actually consumes
 * (manifest fields plus the discovered {@code tools/}/{@code skills/}/{@code subagents/}
 * capabilities). Fields left unset here fall back to caller-supplied defaults
 * (e.g. {@code pluginId} defaults to the plugin's directory name) rather than being
 * defaulted inside this class, since the manifest alone doesn't know its own directory.
 */
@Data
@Builder
public class PluginManifest {

    private String pluginId;

    private String name;

    private String version;

    private String description;

    /** Claude Code's {@code plugin.json} uses "keywords" for this; regnexe's native
     *  {@code plugin.yaml} uses "tags" — the loader that builds this object accepts either. */
    private List<String> tags;
}
