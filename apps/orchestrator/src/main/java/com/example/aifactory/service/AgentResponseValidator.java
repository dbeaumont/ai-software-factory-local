package com.example.aifactory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Validates the small, machine-enforced parts of agent output contracts. */
@Component
public class AgentResponseValidator {
    private final ObjectMapper objectMapper;

    public AgentResponseValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void requireImplementablePlan(String response) {
        JsonNode root = parse(response, "Planner");
        String status = requiredText(root, "status", "Planner");
        if (!"IMPLEMENTABLE".equals(status)) {
            throw new IllegalStateException("Planner blocked the run with status " + status);
        }
        String risk = requiredText(root, "risk_level", "Planner");
        if ("R3".equals(risk) || "R4".equals(risk)) {
            throw new IllegalStateException("Planner classified the run as " + risk + "; prior expert approval is required");
        }
        if (!"R0".equals(risk) && !"R1".equals(risk) && !"R2".equals(risk)) {
            throw new IllegalStateException("Planner returned unsupported risk level " + risk);
        }
    }

    public void requireReviewAllowsApproval(String response) {
        requireReviewAllowsApproval(summarizeReview(response));
    }

    public ReviewSummary summarizeReview(String response) {
        JsonNode root = parse(response, "Reviewer");
        String decision = requiredText(root, "decision", "Reviewer");
        List<ReviewFinding> findings = new ArrayList<>();
        for (JsonNode finding : root.path("findings")) {
            findings.add(new ReviewFinding(
                    finding.path("file").asText("<non précisé>"),
                    finding.path("severity").asText("<non précisé>"),
                    finding.path("rule").asText("<règle non précisée>"),
                    finding.path("fix").asText("<correctif non précisé>")));
        }
        return new ReviewSummary(decision, List.copyOf(findings));
    }

    public void requireReviewAllowsApproval(ReviewSummary review) {
        String decision = review.decision();
        if ("REJECT".equals(decision)) {
            throw new IllegalStateException("Reviewer rejected the change (" + review.findingCounts() + ")");
        }
        if (!"ACCEPT".equals(decision) && !"ACCEPT_WITH_COMMENTS".equals(decision)) {
            throw new IllegalStateException("Reviewer returned unsupported decision " + decision);
        }
        for (ReviewFinding finding : review.findings()) {
            if ("blocker".equals(finding.severity())) {
                throw new IllegalStateException("Reviewer reported blocker findings");
            }
        }
    }

    public record ReviewFinding(String file, String severity, String rule, String fix) { }

    public record ReviewSummary(String decision, List<ReviewFinding> findings) {
        public String findingCounts() {
            long blockers = findings.stream().filter(finding -> "blocker".equals(finding.severity())).count();
            long majors = findings.stream().filter(finding -> "major".equals(finding.severity())).count();
            long minors = findings.stream().filter(finding -> "minor".equals(finding.severity())).count();
            return "blocker=%d, major=%d, minor=%d".formatted(blockers, majors, minors);
        }
    }

    public void requireTesterReport(String response) {
        JsonNode root = parse(response, "Tester");
        requireArray(root, "coverage_gaps", "Tester");
        requireArray(root, "test_cases", "Tester");
        requireArray(root, "evidence", "Tester");
        requireArray(root, "unverified", "Tester");
    }

    private JsonNode parse(String response, String agent) {
        try {
            return objectMapper.readTree(stripJsonFence(response));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(agent + " response must be valid JSON", e);
        }
    }

    private static String requiredText(JsonNode root, String field, String agent) {
        JsonNode value = root == null ? null : root.path(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException(agent + " response is missing required field '" + field + "'");
        }
        return value.asText();
    }

    private static void requireArray(JsonNode root, String field, String agent) {
        if (root == null || !root.path(field).isArray()) {
            throw new IllegalStateException(agent + " response is missing required array '" + field + "'");
        }
    }

    static String stripJsonFence(String response) {
        String value = response == null ? "" : response.strip();
        if (!value.startsWith("```")) return value;
        int newline = value.indexOf('\n');
        if (newline < 0) return value;
        value = value.substring(newline + 1);
        return value.endsWith("```") ? value.substring(0, value.length() - 3).strip() : value.strip();
    }
}
