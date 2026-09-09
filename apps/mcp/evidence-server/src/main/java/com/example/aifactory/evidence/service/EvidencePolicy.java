package com.example.aifactory.evidence.service;

import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class EvidencePolicy {
    private static final java.util.Set<String> AGENT_ROLES = java.util.Set.of(
            "supervisor", "architecture-agent", "impact-analysis", "dependencies-contracts", "code-agent",
            "developer", "patch-repair", "test-agent", "test-design", "test-evidence", "security-agent",
            "threat-model", "security-findings", "independent-reviewer");
    private static final java.util.Set<String> LEGAL_HOLD_ACTORS = java.util.Set.of(
            "security-officer", "legal-officer");
    private static final java.util.Set<String> SUMMARY_ACTORS = java.util.Set.of(
            "workflow", "supervisor", "test-agent", "test-evidence", "security-agent",
            "security-findings", "independent-reviewer", "planner", "reviewer");
    private static final java.util.Set<String> WORKFLOW_RAW_READ_PURPOSES = java.util.Set.of(
            "repair-patch", "pipeline-a2a-result", "pipeline-test-consolidation",
            "hierarchical-specialist-result", "prepare-developer-tasks", "prepare-short-developer-task",
            "accept-developer-patch", "project-developer-patch", "prepare-native-patch-repair",
            "accept-native-patch-repair", "project-native-patch-repair");
    private static final Map<String, Rule> RULES = Map.ofEntries(
            Map.entry("plan", new Rule("INTERNAL", 90)), Map.entry("patch", new Rule("INTERNAL", 90)),
            Map.entry("patch-candidate", new Rule("INTERNAL", 90)),
            Map.entry("patch-validation-error", new Rule("INTERNAL", 90)),
            Map.entry("a2a-input-plan", new Rule("INTERNAL", 90)),
            Map.entry("a2a-input-generate-patch", new Rule("INTERNAL", 90)),
            Map.entry("a2a-input-repair-patch", new Rule("INTERNAL", 90)),
            Map.entry("a2a-input-assess-tests", new Rule("INTERNAL", 90)),
            Map.entry("a2a-input-review", new Rule("INTERNAL", 90)),
            Map.entry("specialist-task", new Rule("INTERNAL", 90)),
            Map.entry("code-task", new Rule("INTERNAL", 90)),
            Map.entry("patch-repair-task", new Rule("INTERNAL", 90)),
            Map.entry("agent-result", new Rule("INTERNAL", 180)),
            Map.entry("code-patch", new Rule("INTERNAL", 90)),
            Map.entry("evaluation", new Rule("INTERNAL", 180)), Map.entry("integration", new Rule("INTERNAL", 90)),
            Map.entry("metadata", new Rule("INTERNAL", 180)), Map.entry("tests", new Rule("INTERNAL", 90)),
            Map.entry("tests-deterministic", new Rule("INTERNAL", 90)),
            Map.entry("quality", new Rule("INTERNAL", 180)),
            Map.entry("security", new Rule("CONFIDENTIAL", 365)),
            Map.entry("sonar", new Rule("INTERNAL", 180)), Map.entry("sbom", new Rule("INTERNAL", 365)),
            Map.entry("trivy", new Rule("CONFIDENTIAL", 365)), Map.entry("review", new Rule("CONFIDENTIAL", 365)),
            Map.entry("approval", new Rule("CONFIDENTIAL", 365)), Map.entry("manifest", new Rule("CONFIDENTIAL", 365)));

    public Rule requireWrite(String type, String actor) {
        Rule rule = require(type);
        if (!("workflow".equals(actor) || "agent-result".equals(type) && AGENT_ROLES.contains(actor))) {
            throw new SecurityException("actor cannot write this evidence type");
        }
        return rule;
    }

    public Rule require(String type) {
        Rule rule = RULES.get(type);
        if (rule == null) {
            String safeType = type != null && type.matches("[a-z0-9-]{1,64}") ? type : "invalid";
            throw new SecurityException("unknown evidence type: " + safeType);
        }
        return rule;
    }

    public Rule requireSummary(String type, String actor) {
        Rule rule = require(type);
        if (!SUMMARY_ACTORS.contains(actor)) {
            throw new SecurityException("actor cannot inspect evidence summary");
        }
        return rule;
    }

    public Rule requireRead(String type, String actor, String purpose) {
        Rule rule = require(type);
        boolean workflowInternalPurpose = "workflow".equals(actor) && (WORKFLOW_RAW_READ_PURPOSES.contains(purpose)
                || (purpose != null && purpose.matches("apply-patch-integration:[0-9a-f]{64}")));
        boolean agentExecutionInput = AGENT_ROLES.contains(actor) && "agent-execution-input".equals(purpose);
        if (!("workflow".equals(actor) || "reviewer".equals(actor) || "independent-reviewer".equals(actor)
                || agentExecutionInput)
                || !("human-review".equals(purpose) || "incident-investigation".equals(purpose)
                || "projection-recovery".equals(purpose) || "legacy-task-read".equals(purpose)
                || workflowInternalPurpose || agentExecutionInput)
                || ("approval".equals(type) && !"workflow".equals(actor))) {
            throw new SecurityException("raw evidence read is not authorized");
        }
        return rule;
    }

    public void requireLegalHoldActor(String actor) {
        if (!LEGAL_HOLD_ACTORS.contains(actor)) {
            throw new SecurityException("actor cannot manage a legal hold");
        }
    }

    public record Rule(String classification, int retentionDays) {}
}
