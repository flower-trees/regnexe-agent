package org.salt.regnexe.agent.core.task.state;

import java.util.List;
import lombok.Data;

/** Caller-owned complete acceptance criteria for a standalone offline text task. */
@Data
public class OfflineTextReview {
    private String taskSummary;
    private List<String> acceptanceCriteria;

    public static OfflineTextReview snapshot(OfflineTextReview context) {
        if (context == null) {
            return null;
        }
        if (context.taskSummary == null || context.taskSummary.isBlank()
                || context.acceptanceCriteria == null || context.acceptanceCriteria.isEmpty()
                || context.acceptanceCriteria.stream().anyMatch(item -> item == null || item.isBlank())) {
            throw new IllegalArgumentException("Offline text review requires a task summary and complete criteria");
        }
        var copy = new OfflineTextReview();
        copy.taskSummary = context.taskSummary;
        copy.acceptanceCriteria = List.copyOf(context.acceptanceCriteria);
        return copy;
    }
}
