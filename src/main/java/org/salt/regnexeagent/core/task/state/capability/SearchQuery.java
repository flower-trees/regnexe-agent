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

package org.salt.regnexeagent.core.task.state.capability;

import lombok.Data;

import java.util.List;

/**
 * Input to the Searcher for one Marketplace query
 */
@Data
public class SearchQuery {

    private String goal;

    /**
     * Capability ids to exclude; passed in when re-searching after a failed round
     */
    private List<String> excludeIds;

    /**
     * Search direction hint from the previous round's Reflector
     */
    private String reflectionHint;

    private int topK;
}
