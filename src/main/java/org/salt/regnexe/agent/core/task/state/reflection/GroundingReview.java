package org.salt.regnexe.agent.core.task.state.reflection;

import java.util.List;
import lombok.Data;

/** Per-output-clause review; source pointers refer only to authoritativeFacts. */
@Data
public class GroundingReview {
    private List<ClaimCheck> checks;

    @Data
    public static class ClaimCheck {
        private int claimId;
        private String assessment;
        private List<String> sourcePointers;
    }
}
