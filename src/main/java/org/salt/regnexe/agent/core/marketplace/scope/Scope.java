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

/**
 * Where an {@code enabled.yml} declaration came from.
 *
 * <p>Only {@link #USER} and {@link #PROJECT} currently have a concrete on-disk home
 * ({@code ~/.rex/enabled.yml} and {@code project/.rex/enabled.yml}). {@link #LOCAL} and
 * {@link #MANAGED} are reserved for a future round — it's still an open question whether a
 * gitignored per-developer override layer (LOCAL) is needed, and whether/what an
 * enterprise-managed layer (MANAGED) looks like. Declaring them here now costs nothing and
 * lets {@link ScopeResolver} callers reference a stable enum once those layers exist, without
 * this enum encoding a priority order itself — see {@link ScopeResolver} for why.
 */
public enum Scope {
    MANAGED,
    PROJECT,
    LOCAL,
    USER
}
