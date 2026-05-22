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

package org.salt.regnexeagent.core.task.worker;

import lombok.extern.slf4j.Slf4j;
import org.salt.function.flow.context.IContextBus;
import org.salt.function.flow.node.FlowNode;
import org.salt.regnexeagent.core.event.AgentEvent;
import org.salt.regnexeagent.core.event.AgentEventListener;
import org.salt.regnexeagent.core.event.EventType;
import org.salt.regnexeagent.core.market.Marketplace;
import org.salt.regnexeagent.core.task.state.RoundRecord;
import org.salt.regnexeagent.core.task.state.TaskExecutionState;
import org.salt.regnexeagent.core.task.state.capability.CapabilitySearchResult;
import org.salt.regnexeagent.core.task.state.capability.SearchQuery;
import org.salt.regnexeagent.core.task.state.reflection.ReflectionHint;
import org.salt.regnexeagent.core.task.store.TaskStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Queries the Marketplace for capability candidates matching the current goal.
 * First round always runs; subsequent rounds run only when the previous
 * ReflectionHint requests a re-search.
 */
@Slf4j
public class CapabilitySearcher extends FlowNode<Object, Object> implements Worker {

    @Override
    public Object process(Object input) {
        IContextBus bus = getContextBus();
        TaskExecutionState state = bus.getTransmit(ContextBusKeys.STATE);
        Marketplace marketplace = bus.getTransmit(ContextBusKeys.MARKETPLACE);
        AgentEventListener listener = bus.getTransmit(ContextBusKeys.EVENT_LISTENER);

        int roundNumber = state.getCurrentRound() + 1;
        state.setCurrentRound(roundNumber);

        RoundRecord round = new RoundRecord();
        round.setRoundNumber(roundNumber);
        round.setStartedAt(System.currentTimeMillis());
        if (state.getRounds() == null) {
            state.setRounds(new ArrayList<>());
        }
        state.getRounds().add(round);

        ReflectionHint lastHint = lastHint(state);
        boolean shouldSearch = roundNumber == 1
                || (lastHint != null && lastHint.isRequestResearch());

        if (shouldSearch && marketplace != null) {
            SearchQuery query = new SearchQuery();
            query.setGoal(state.getRequest().getGoal());
            query.setTopK(50);
            if (lastHint != null) {
                query.setExcludeIds(lastHint.getExcludeCapabilityIds());
                query.setReflectionHint(lastHint.getSearchDirection());
            }

            CapabilitySearchResult result = marketplace.search(query);

            if (state.getSearchResults() == null) {
                state.setSearchResults(new ArrayList<>());
            }
            state.getSearchResults().add(result);
            round.setSearch(result);
            round.setSearchResultVersion(state.getSearchResults().size() - 1);
            bus.putTransmit(ContextBusKeys.CANDIDATES, result.getCandidates());

            String names = result.getCandidates().stream()
                    .map(c -> c.getName()).collect(java.util.stream.Collectors.joining(", "));
            listener.onEvent(AgentEvent.of(state.getTaskId(), roundNumber, EventType.SEARCH_COMPLETED,
                    "Found " + result.getCandidates().size() + " capabilities: " + names));
            log.debug("Round {}: searched, found {} candidates",
                    roundNumber, result.getCandidates().size());
        } else {
            int lastVersion = state.getSearchResults() != null
                    ? state.getSearchResults().size() - 1 : 0;
            round.setSearchResultVersion(lastVersion);
            List<?> lastCandidates = state.getSearchResults() != null && !state.getSearchResults().isEmpty()
                    ? state.getSearchResults().get(lastVersion).getCandidates()
                    : List.of();
            bus.putTransmit(ContextBusKeys.CANDIDATES, lastCandidates);

            listener.onEvent(AgentEvent.of(state.getTaskId(), roundNumber, EventType.SEARCH_COMPLETED,
                    "Reusing cached capabilities (version " + lastVersion + ")"));
            log.debug("Round {}: skipped search, reusing version {}", roundNumber, lastVersion);
        }

        state.setUpdatedAt(System.currentTimeMillis());

        TaskStore taskStore = bus.getTransmit(ContextBusKeys.TASK_STORE);
        if (taskStore != null) taskStore.save(state);
        return null;
    }

    private ReflectionHint lastHint(TaskExecutionState state) {
        List<RoundRecord> rounds = state.getRounds();
        if (rounds == null || rounds.size() < 2) return null;
        for (int i = rounds.size() - 2; i >= 0; i--) {
            RoundRecord r = rounds.get(i);
            if (r.getReflection() != null && r.getReflection().getHintForNext() != null) {
                return r.getReflection().getHintForNext();
            }
        }
        return null;
    }

    @Override
    public void stop() {
        // Fast retrieval step — no long-running inner executor to stop
    }
}
