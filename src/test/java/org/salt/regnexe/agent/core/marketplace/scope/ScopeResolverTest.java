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

import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class ScopeResolverTest {

    private final ScopeResolver resolver = new ScopeResolver();

    @Test
    public void emptyLayers_resolveToEmptyMap_andEverythingDefaultsEnabled() {
        Map<String, Boolean> resolved = resolver.resolve(List.of());

        Assert.assertTrue(resolved.isEmpty());
        Assert.assertTrue("undeclared plugin defaults to enabled", resolver.isEnabled(resolved, "weather-tools@personal"));
    }

    @Test
    public void singleLayer_isReflectedAsIs() {
        Map<String, Boolean> resolved = resolver.resolve(List.of(
                new ScopedEnabledState(Scope.USER, Map.of("weather-tools@personal", false))
        ));

        Assert.assertFalse(resolver.isEnabled(resolved, "weather-tools@personal"));
    }

    @Test
    public void laterLayerOverridesEarlierLayerForSameKey() {
        // caller-supplied order = lowest priority first; PROJECT here is passed after USER,
        // so PROJECT wins — this test isn't asserting a specific global priority (see class
        // javadoc: ScopeResolver takes no position on that), just that "later wins".
        Map<String, Boolean> resolved = resolver.resolve(List.of(
                new ScopedEnabledState(Scope.USER, Map.of("weather-tools@personal", true)),
                new ScopedEnabledState(Scope.PROJECT, Map.of("weather-tools@personal", false))
        ));

        Assert.assertFalse(resolver.isEnabled(resolved, "weather-tools@personal"));
    }

    @Test
    public void layersDoNotInterfereWithUnrelatedKeys() {
        Map<String, Boolean> resolved = resolver.resolve(List.of(
                new ScopedEnabledState(Scope.USER, Map.of("weather-tools@personal", false)),
                new ScopedEnabledState(Scope.PROJECT, Map.of("security-review@default", true))
        ));

        Assert.assertFalse(resolver.isEnabled(resolved, "weather-tools@personal"));
        Assert.assertTrue(resolver.isEnabled(resolved, "security-review@default"));
        Assert.assertTrue("still undeclared elsewhere", resolver.isEnabled(resolved, "unrelated-plugin@default"));
    }

    @Test
    public void nullEnabledMapInALayerIsIgnoredRatherThanThrowing() {
        Map<String, Boolean> resolved = resolver.resolve(List.of(
                new ScopedEnabledState(Scope.USER, null),
                new ScopedEnabledState(Scope.PROJECT, Map.of("security-review@default", true))
        ));

        Assert.assertTrue(resolver.isEnabled(resolved, "security-review@default"));
    }
}
