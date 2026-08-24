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

package org.salt.regnexe.agent.core.marketplace;

import org.junit.Assert;
import org.junit.Test;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityType;
import org.salt.regnexe.agent.core.marketplace.plugin.PluginDescriptor;
import org.salt.jlangchain.rag.tools.Tool;

import java.util.List;

/**
 * A single plugin with two capabilities that happen to compute the same capabilityId (e.g. a
 * tool and a skill both named "foo" under pluginId "bar" → both "bar.foo") used to install
 * silently — the duplicate check only compared against the global registry, never within the
 * batch being installed. {@link SimpleMarketplace#resolveDescriptor} matches by id string only
 * (not type), so the second capability would have been an unreachable dead entry with no error
 * at all.
 */
public class SimpleMarketplaceTest {

    @Test
    public void install_duplicateCapabilityIdWithinSamePlugin_throws() {
        SimpleMarketplace marketplace = new SimpleMarketplace();
        CapabilityDescriptor toolCap = CapabilityDescriptor.builder()
                .capabilityId("bar.foo").pluginId("bar").type(CapabilityType.MCP_TOOL)
                .tool(Tool.builder().name("foo").description("a tool").params("").func(args -> "ok").build())
                .build();
        CapabilityDescriptor skillCap = CapabilityDescriptor.builder()
                .capabilityId("bar.foo").pluginId("bar").type(CapabilityType.SKILL)
                .build();
        PluginDescriptor plugin = PluginDescriptor.builder()
                .pluginId("bar").version("1.0").name("bar").description("test plugin")
                .tags(List.of())
                .capabilities(List.of(toolCap, skillCap))
                .build();

        Assert.assertThrows(IllegalStateException.class, () -> marketplace.install(plugin));
        // the whole plugin must not be partially installed
        Assert.assertTrue(marketplace.listEnabled().isEmpty());
    }

    @Test
    public void install_distinctCapabilityIdsWithinSamePlugin_succeeds() {
        SimpleMarketplace marketplace = new SimpleMarketplace();
        CapabilityDescriptor toolCap = CapabilityDescriptor.builder()
                .capabilityId("bar.foo").pluginId("bar").type(CapabilityType.MCP_TOOL)
                .tool(Tool.builder().name("foo").description("a tool").params("").func(args -> "ok").build())
                .build();
        CapabilityDescriptor otherToolCap = CapabilityDescriptor.builder()
                .capabilityId("bar.baz").pluginId("bar").type(CapabilityType.MCP_TOOL)
                .tool(Tool.builder().name("baz").description("another tool").params("").func(args -> "ok").build())
                .build();
        PluginDescriptor plugin = PluginDescriptor.builder()
                .pluginId("bar").version("1.0").name("bar").description("test plugin")
                .tags(List.of())
                .capabilities(List.of(toolCap, otherToolCap))
                .build();

        marketplace.install(plugin);

        Assert.assertEquals(1, marketplace.listEnabled().size());
        Assert.assertNotNull(marketplace.resolveDescriptor("bar.foo"));
        Assert.assertNotNull(marketplace.resolveDescriptor("bar.baz"));
    }
}
