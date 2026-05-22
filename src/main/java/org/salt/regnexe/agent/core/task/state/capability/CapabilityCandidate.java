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

package org.salt.regnexe.agent.core.task.state.capability;

import lombok.Data;
import org.salt.regnexe.agent.core.common.enums.CapabilityType;

import java.util.Map;

/**
 * A single capability candidate returned by Searcher
 */
@Data
public class CapabilityCandidate {

    private String capabilityId;

    private String pluginId;

    private CapabilityType type;

    private String name;

    private String description;

    private double score;

    private Map<String, Object> metadata;
}
