package com.example.aifactory.evidence.service;

import org.springframework.stereotype.Service;
import java.util.Map;

@Service
public class EvidencePolicy {
    private static final Map<String, Rule> RULES = Map.ofEntries(
            Map.entry("plan", new Rule("INTERNAL", 90)), Map.entry("patch", new Rule("INTERNAL", 90)),
            Map.entry("evaluation", new Rule("INTERNAL", 180)), Map.entry("integration", new Rule("INTERNAL", 90)),
            Map.entry("metadata", new Rule("INTERNAL", 180)), Map.entry("tests", new Rule("INTERNAL", 90)),
            Map.entry("sonar", new Rule("INTERNAL", 180)), Map.entry("sbom", new Rule("INTERNAL", 365)),
            Map.entry("trivy", new Rule("CONFIDENTIAL", 365)), Map.entry("review", new Rule("CONFIDENTIAL", 365)),
            Map.entry("approval", new Rule("CONFIDENTIAL", 365)), Map.entry("manifest", new Rule("CONFIDENTIAL", 365)));

    public Rule requireWrite(String type, String actor) {
        Rule rule = require(type);
        if (!"workflow".equals(actor)) throw new SecurityException("actor cannot write this evidence type");
        return rule;
    }

    public Rule require(String type) {
        Rule rule = RULES.get(type);
        if (rule == null) throw new SecurityException("unknown evidence type");
        return rule;
    }

    public Rule requireSummary(String type, String actor) {
        Rule rule = require(type);
        if (!("workflow".equals(actor) || "planner".equals(actor) || "reviewer".equals(actor))) {
            throw new SecurityException("actor cannot inspect evidence summary");
        }
        return rule;
    }

    public Rule requireRead(String type, String actor, String purpose) {
        Rule rule = require(type);
        if (!("workflow".equals(actor) || "reviewer".equals(actor))
                || !("human-review".equals(purpose) || "incident-investigation".equals(purpose))
                || ("approval".equals(type) && !"workflow".equals(actor))) {
            throw new SecurityException("raw evidence read is not authorized");
        }
        return rule;
    }

    public record Rule(String classification, int retentionDays) {}
}
