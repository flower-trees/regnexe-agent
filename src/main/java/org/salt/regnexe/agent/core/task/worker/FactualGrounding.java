package org.salt.regnexe.agent.core.task.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.salt.regnexe.agent.core.common.enums.ReflectionAction;
import org.salt.regnexe.agent.core.task.state.TaskExecutionState;
import org.salt.regnexe.agent.core.task.state.reflection.GroundingReview.ClaimCheck;
import org.salt.regnexe.agent.core.task.state.reflection.ReflectionDecision;
import org.salt.regnexe.agent.core.task.state.reflection.ReflectionHint;

/** Opt-in evidence/coverage protocol. Pointer validity is not proof of semantic entailment. */
public final class FactualGrounding {
    private static final ObjectMapper JSON = new ObjectMapper();
    private FactualGrounding() {}

    public static Map<String, Object> snapshot(Map<String, Object> facts) {
        if (facts == null) {
            return null;
        }
        if (facts.isEmpty()) {
            throw new IllegalArgumentException("Authoritative facts must not be empty");
        }
        try {
            return JSON.readValue(JSON.writeValueAsBytes(facts), new TypeReference<Map<String, Object>>() {});
        } catch (Exception error) {
            throw new IllegalArgumentException("Authoritative facts must be JSON data", error);
        }
    }

    public static String instructions(Map<String, Object> facts) {
        if (facts == null) {
            return "";
        }
        return """

                FACTUAL GROUNDING REVIEW IS REQUIRED FOR THIS TASK.
                The authoritative facts block is the only source of product-specific assertions.
                The Goal, original/before text, execution answer, plans and generic domain knowledge
                are not additional evidence. Unknown or omitted properties must not be invented.
                Review EVERY supplied claim ID, including qualitative adjectives, usability,
                programming requirements and scenario/demand coverage, not only numeric parameters.
                You may explain the meaning of a supplied capability, but do not infer unverified
                product properties or benefits from it. Plausibility is not factual support.
                Add groundingReview to the normal decision JSON:
                "groundingReview":{"checks":[{"claimId":1,"assessment":"SUPPORTED",
                "sourcePointers":["/payload_kg"]}]}
                Emit one check per claim ID, no omissions or duplicates. assessment must be
                SUPPORTED, UNSUPPORTED or UNCERTAIN. For SUPPORTED cite existing JSON pointers
                to scalar facts, and verify that those facts actually entail the ENTIRE clause.
                Merely citing a real fact does not justify unrelated adjectives or benefits.
                For UNSUPPORTED/UNCERTAIN use an empty sourcePointers list. These checks require
                CONTINUE with a concrete correction hint, never FINISH. Do not invent evidence.
                """;
    }

    public static List<String> claims(String output) {
        List<String> text = new ArrayList<>();
        try {
            collect(JSON.readTree(output), text);
        } catch (Exception ignored) {
            if (output != null) {
                text.add(output);
            }
        }
        List<String> clauses = new ArrayList<>();
        for (String value : text) {
            for (String clause : value.split("[，,。；;！？!?\\r\\n]+")) {
                if (!clause.isBlank()) {
                    clauses.add(clause.strip());
                }
            }
        }
        return clauses;
    }

    private static void collect(JsonNode node, List<String> text) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            text.add(node.asText());
        } else if (node.isContainerNode()) {
            node.forEach(child -> collect(child, text));
        }
    }

    public static String prompt(Map<String, Object> facts, String output) {
        if (facts == null) {
            return "";
        }
        try {
            StringBuilder text = new StringBuilder("\nAuthoritative facts (JSON pointer root):\n")
                    .append(JSON.writeValueAsString(facts)).append("\nClaims to review:\n");
            List<String> claims = claims(output);
            for (int i = 0; i < claims.size(); i++) {
                text.append(i + 1).append(": ").append(JSON.writeValueAsString(claims.get(i))).append("\n");
            }
            return text.toString();
        } catch (Exception error) {
            throw new IllegalArgumentException("Cannot render factual review", error);
        }
    }

    public static void enforce(TaskExecutionState state, String output, ReflectionDecision decision) {
        Map<String, Object> facts = state.getRequest().getAuthoritativeFacts();
        if (facts == null) {
            return;
        }
        List<String> claims = claims(output);
        Map<Integer, ClaimCheck> byId = new HashMap<>();
        List<String> problems = new ArrayList<>();
        boolean incomplete = claims.isEmpty() || decision.getGroundingReview() == null
                || decision.getGroundingReview().getChecks() == null;
        if (!incomplete) {
            for (ClaimCheck check : decision.getGroundingReview().getChecks()) {
                if (check == null || check.getClaimId() < 1 || check.getClaimId() > claims.size()
                        || byId.putIfAbsent(check.getClaimId(), check) != null) {
                    incomplete = true;
                }
            }
        }
        JsonNode source = JSON.valueToTree(facts);
        for (int i = 1; i <= claims.size(); i++) {
            ClaimCheck check = byId.get(i);
            if (check == null) {
                incomplete = true;
                continue;
            }
            if ("UNSUPPORTED".equals(check.getAssessment()) || "UNCERTAIN".equals(check.getAssessment())) {
                problems.add("Claim " + i + ": " + claims.get(i - 1));
            } else if ("SUPPORTED".equals(check.getAssessment())) {
                if (check.getSourcePointers() == null || check.getSourcePointers().isEmpty()) {
                    incomplete = true;
                } else {
                    for (String pointer : check.getSourcePointers()) {
                        try {
                            JsonNode evidence = pointer == null || !pointer.startsWith("/") ? null : source.at(pointer);
                            if (evidence == null || evidence.isMissingNode() || evidence.isNull() || evidence.isContainerNode()) {
                                incomplete = true;
                            }
                        } catch (IllegalArgumentException error) {
                            incomplete = true;
                        }
                    }
                }
            } else {
                incomplete = true;
            }
        }
        if (incomplete || !problems.isEmpty()) {
            String reason = incomplete ? "FACTUAL_REVIEW_INCOMPLETE" : "UNSUPPORTED_CLAIMS";
            state.setTerminationReason(reason);
            if (decision.getAction() == ReflectionAction.FINISH) {
                decision.setAction(ReflectionAction.CONTINUE);
            }
            decision.setReason(reason + (problems.isEmpty() ? ": missing/invalid claim coverage or evidence" : ": " + String.join("; ", problems)));
            if (decision.getAction() == ReflectionAction.CONTINUE) {
                ReflectionHint hint = new ReflectionHint();
                hint.setPlanAdjustment("Remove or rewrite unsupported clauses using only authoritative facts. " + decision.getReason());
                hint.setReason(reason);
                decision.setHintForNext(hint);
            }
        } else if ("FACTUAL_REVIEW_INCOMPLETE".equals(state.getTerminationReason())
                || "UNSUPPORTED_CLAIMS".equals(state.getTerminationReason())) {
            state.setTerminationReason(null);
        }
    }
}
