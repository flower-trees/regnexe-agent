package org.salt.regnexe.agent.core.task.worker;

import java.util.LinkedHashMap;
import java.util.List;
import org.salt.regnexe.agent.core.marketplace.Marketplace;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityDescriptor;
import org.salt.regnexe.agent.core.marketplace.capability.CapabilityType;
import org.salt.regnexe.agent.core.task.state.TaskRequest;
import org.salt.regnexe.agent.core.task.state.capability.CapabilityCandidate;
import org.salt.regnexe.agent.core.task.state.plan.PlanOutput;

/** Single explicit Skill constraint. No standalone Skill executor or silent Auto fallback. */
public final class RequiredSkillPolicy {
    private RequiredSkillPolicy() {
    }

    public static CapabilityDescriptor validate(TaskRequest request, Marketplace marketplace) {
        String id = request.getRequiredSkillId();
        if (id == null) {
            return null;
        }
        CapabilityDescriptor cap = marketplace == null ? null : marketplace.resolveDescriptor(id);
        if (id.isBlank() || cap == null) {
            throw new IllegalArgumentException("Unknown skill or disabled skill: " + id);
        }
        if (cap.getType() != CapabilityType.SKILL || cap.getSkillConfig() == null) {
            throw new IllegalArgumentException("Not a skill capability or failed to load: " + id);
        }
        return cap;
    }

    public static List<CapabilityCandidate> candidates(TaskRequest request, Marketplace marketplace) {
        CapabilityDescriptor cap = validate(request, marketplace);
        CapabilityCandidate candidate = new CapabilityCandidate();
        candidate.setCapabilityId(cap.getCapabilityId());
        candidate.setPluginId(cap.getPluginId());
        candidate.setType(cap.getType());
        candidate.setName(cap.getName());
        candidate.setDescription(cap.getDescription());
        // Extra allowedTools are intentionally unavailable for the first single-Skill version.
        return List.of(candidate);
    }

    public static void enforcePlan(TaskRequest request, PlanOutput plan) {
        String id = request.getRequiredSkillId();
        if (id == null) {
            return;
        }
        plan.setSelectedCapabilityIds(List.of(id));
        var descriptions = new LinkedHashMap<String, String>();
        String input = plan.getCapabilityInputDescriptions() == null ? null
                : plan.getCapabilityInputDescriptions().get(id);
        descriptions.put(id, input == null || input.isBlank() ? request.getGoal() : input);
        plan.setCapabilityInputDescriptions(descriptions);
        plan.setNarrative("Mandatory Skill: " + id
                + ". Do not substitute or invoke additional Skills/sub-agents. Use only base tools and this Skill's intrinsic tools.\n"
                + (plan.getNarrative() == null ? "" : plan.getNarrative()));
    }
}
