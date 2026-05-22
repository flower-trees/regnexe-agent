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

package org.salt.regnexe.agent.core.llm;

import lombok.Value;

/**
 * Identifies a model unambiguously by vendor + model name.
 * When vendor is null, {@link DefaultModelProvider} routes by model-name prefix.
 *
 * <pre>
 * ModelSpec.of("deepseek-v4-flash")               // auto-route via prefix
 * ModelSpec.of("aliyun", "deepseek-v3")   // explicit vendor
 * </pre>
 */
@Value
public class ModelSpec {

    String vendor; // nullable
    String model;

    public static ModelSpec of(String model) {
        return new ModelSpec(null, model);
    }

    public static ModelSpec of(String vendor, String model) {
        return new ModelSpec(vendor, model);
    }

    public static ModelSpec of(Vendor vendor, String model) {
        return new ModelSpec(vendor.getValue(), model);
    }
}
