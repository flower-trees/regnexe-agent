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

package org.salt.heliosagent.core.market.plugin;

import lombok.Data;
import org.salt.heliosagent.core.common.enums.CapabilityType;

import java.util.List;

/**
 * Describes a single capability exposed by a plugin.
 * The description and tags are what Searcher and Planner see.
 */
@Data
public class CapabilityDescriptor {

    private String capabilityId;

    private String pluginId;

    private CapabilityType type;

    private String name;

    /**
     * Natural-language description for Searcher / Planner
     */
    private String description;

    private List<String> tags;

    /**
     * Content of the capability's .md definition file (SKILL.md / AGENT.md).
     * Used by Marketplace.resolve() to construct the Tool instance.
     */
    private String definitionMd;
}
