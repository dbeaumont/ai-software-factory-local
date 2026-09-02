package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Enforces workflow-owned policy decisions on every security risk acceptance or downgrade. */
@Component
public final class SecurityDecisionValidator {
    private static final Set<String> SEVERITIES = Set.of("UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Map<String, Integer> SEVERITY_RANK = Map.of(
            "LOW", 1, "MEDIUM", 2, "HIGH", 3, "CRITICAL", 4);

    public JsonNode validate(JsonNode assessment, JsonNode suppliedFindings,
                             Set<PolicyDecision> suppliedDecisions) {
        if (!assessment.path("normalized_findings").equals(suppliedFindings)) {
            throw rejected("normalized findings were altered");
        }

        Map<String, JsonNode> findings = index(suppliedFindings.path("findings"), "id", "finding");
        Map<String, JsonNode> threats = index(assessment.path("threats"), "threat_id", "threat");
        Set<PolicyDecision> used = new HashSet<>();
        for (JsonNode node : assessment.path("risk_decisions")) {
            PolicyDecision decision;
            try {
                decision = PolicyDecision.from(node);
            } catch (IllegalArgumentException exception) {
                throw rejected(exception.getMessage());
            }
            if (!used.add(decision)) throw rejected("risk decision is repeated");
            if (!suppliedDecisions.contains(decision)) {
                throw rejected("risk decision was not explicitly supplied by workflow");
            }
            requirePolicyTrace(assessment, decision.policyDecisionUri(), decision.policyDecisionDigest());
            requireTarget(decision, findings, threats);
        }

        for (JsonNode threat : assessment.path("threats")) {
            if ("ACCEPTED".equals(threat.path("status").asText())
                    && used.stream().noneMatch(decision -> decision.accepts(
                    "THREAT", threat.path("threat_id").asText()))) {
                throw rejected("accepted threat lacks an explicit policy decision");
            }
        }
        if ("PASSED".equals(assessment.path("status").asText())
                && !"PASSED".equals(suppliedFindings.path("verdict").asText())) {
            if (findings.isEmpty()) {
                throw rejected("PASSED cannot override an indeterminate or rejected scanner verdict");
            }
            for (String findingId : findings.keySet()) {
                if (used.stream().noneMatch(decision -> decision.targets("FINDING", findingId))) {
                    throw rejected("PASSED leaves finding without an explicit policy decision: " + findingId);
                }
            }
        }
        return assessment;
    }

    private static Map<String, JsonNode> index(JsonNode entries, String idField, String type) {
        Map<String, JsonNode> indexed = new HashMap<>();
        for (JsonNode entry : entries) {
            String id = entry.path(idField).asText();
            if (indexed.putIfAbsent(id, entry) != null) throw rejected("duplicate " + type + ": " + id);
        }
        return indexed;
    }

    private static void requireTarget(PolicyDecision decision, Map<String, JsonNode> findings,
                                      Map<String, JsonNode> threats) {
        JsonNode target = "FINDING".equals(decision.targetType())
                ? findings.get(decision.targetId()) : threats.get(decision.targetId());
        if (target == null) throw rejected("policy decision targets an unknown risk");
        if (!decision.originalSeverity().equals(target.path("severity").asText())) {
            throw rejected("policy decision does not match the original severity");
        }
    }

    private static void requirePolicyTrace(JsonNode assessment, String uri, String digest) {
        boolean declared = false;
        for (JsonNode declaredUri : assessment.path("policy_decision_uris")) {
            declared |= uri.equals(declaredUri.asText());
        }
        boolean evidenced = false;
        for (JsonNode evidence : assessment.path("evidence")) {
            evidenced |= uri.equals(evidence.path("uri").asText())
                    && digest.equals(evidence.path("digest").asText())
                    && "POLICY_DECISION".equals(evidence.path("type").asText());
        }
        if (!declared || !evidenced) throw rejected("policy decision is not fully traceable");
    }

    private static SecurityException rejected(String reason) {
        return new SecurityException("Security assessment rejected: " + reason);
    }

    public record PolicyDecision(String targetType, String targetId, String action,
                                 String originalSeverity, String effectiveSeverity,
                                 String policyDecisionUri, String policyDecisionDigest, String rationale) {
        public PolicyDecision {
            if (!Set.of("FINDING", "THREAT").contains(targetType)
                    || targetId == null || targetId.isBlank() || targetId.length() > 128
                    || !Set.of("ACCEPT_RISK", "DOWNGRADE").contains(action)
                    || !SEVERITIES.contains(originalSeverity) || !SEVERITIES.contains(effectiveSeverity)
                    || policyDecisionUri == null || !policyDecisionUri.startsWith("evidence://")
                    || policyDecisionDigest == null || !policyDecisionDigest.matches("[0-9a-f]{64}")
                    || rationale == null || rationale.isBlank()) {
                throw new IllegalArgumentException("Invalid explicit security policy decision");
            }
            if ("DOWNGRADE".equals(action)) {
                if (!"FINDING".equals(targetType)
                        || !SEVERITY_RANK.containsKey(originalSeverity)
                        || !SEVERITY_RANK.containsKey(effectiveSeverity)
                        || SEVERITY_RANK.get(effectiveSeverity) >= SEVERITY_RANK.get(originalSeverity)) {
                    throw new IllegalArgumentException("DOWNGRADE must lower a concrete finding severity");
                }
            } else if (!originalSeverity.equals(effectiveSeverity)) {
                throw new IllegalArgumentException("ACCEPT_RISK must preserve the original severity");
            }
        }

        static PolicyDecision from(JsonNode node) {
            return new PolicyDecision(node.path("target_type").asText(), node.path("target_id").asText(),
                    node.path("action").asText(), node.path("original_severity").asText(),
                    node.path("effective_severity").asText(), node.path("policy_decision_uri").asText(),
                    node.path("policy_decision_digest").asText(), node.path("rationale").asText());
        }

        boolean accepts(String type, String id) {
            return targets(type, id) && "ACCEPT_RISK".equals(action);
        }

        boolean targets(String type, String id) {
            return targetType.equals(type) && targetId.equals(id);
        }
    }
}
