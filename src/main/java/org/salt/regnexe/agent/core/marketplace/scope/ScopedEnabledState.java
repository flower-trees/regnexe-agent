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

import java.util.Map;

/**
 * One scope's enabled/disabled declarations, as parsed from that scope's {@code enabled.yml}.
 *
 * <p>Keys are the plugin-installer's choice of global id (docs/design/marketplace-plugin-design.md
 * §3.2 uses {@code <plugin-id>@<marketplace-name>}, mirroring Claude Code's
 * {@code plugin-name@marketplace-name}) — {@link ScopeResolver} treats them as opaque strings and
 * doesn't interpret the format.
 */
public record ScopedEnabledState(Scope scope, Map<String, Boolean> enabled) {
}
