package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves contradictions only for classification/authority pairs explicitly covered by host policy. */
@Component
public final class DeterministicContradictionResolver {
    private static final String RESOURCE = "multiagents/policies/contradiction-resolution-policy-v1.yaml";

    private final DecisionAuthorityPolicy authorities;
    private final String policyId;
    private final String policyVersion;
    private final Map<ContradictionClassifier.Classification, Set<DecisionAuthorityPolicy.Authority>> rules;

    public DeterministicContradictionResolver(DecisionAuthorityPolicy authorities) {
        this.authorities = authorities;
        Map<String, Object> root = load();
        policyVersion = required(root, "version");
        if (!"1".equals(policyVersion)) throw invalid("unsupported version " + policyVersion);
        policyId = required(root, "policyId");
        if (!"OPEN".equals(required(root, "defaultDecision"))) throw invalid("defaultDecision must be OPEN");
        if (!"ESCALATE".equals(required(root, "sameAuthorityConflict"))) {
            throw invalid("sameAuthorityConflict must be ESCALATE");
        }
        rules = parseRules(map(root.get("autoResolution"), "autoResolution"));
    }

    public Result resolve(ContradictionClassifier.ClassifiedCandidate contradiction,
                          List<DecisionAuthorityPolicy.Claim> claims) {
        if (contradiction == null || contradiction.classification() == null) {
            throw new IllegalArgumentException("Classified contradiction is required");
        }
        DecisionAuthorityPolicy.Resolution authority = authorities.resolve(claims);
        if (authority.status() == DecisionAuthorityPolicy.Status.ESCALATE) {
            return new Result(Outcome.ESCALATE, null, null, authority.controllingAuthority(),
                    authority.controllingClaimIds(), "conflict at controlling authority");
        }
        Set<DecisionAuthorityPolicy.Authority> allowed = rules.get(contradiction.classification());
        if (allowed == null || !allowed.contains(authority.controllingAuthority())) {
            return new Result(Outcome.OPEN, null, null, authority.controllingAuthority(),
                    authority.controllingClaimIds(), "no deterministic rule covers classification and authority");
        }
        String ruleId = contradiction.classification().name().toLowerCase() + "."
                + authority.controllingAuthority().name().toLowerCase() + "_wins";
        return new Result(Outcome.RESOLVED, authority.verdict(), ruleId, authority.controllingAuthority(),
                authority.controllingClaimIds(), "resolved by explicit deterministic policy rule");
    }

    public String policyId() { return policyId; }

    public String policyVersion() { return policyVersion; }

    private static Map<ContradictionClassifier.Classification, Set<DecisionAuthorityPolicy.Authority>> parseRules(
            Map<String, Object> values) {
        Map<ContradictionClassifier.Classification, Set<DecisionAuthorityPolicy.Authority>> result =
                new EnumMap<>(ContradictionClassifier.Classification.class);
        for (ContradictionClassifier.Classification classification : ContradictionClassifier.Classification.values()) {
            Object configured = values.get(classification.name());
            if (!(configured instanceof List<?> items)) throw invalid("missing rule for " + classification);
            EnumSet<DecisionAuthorityPolicy.Authority> allowed = EnumSet.noneOf(DecisionAuthorityPolicy.Authority.class);
            try {
                items.forEach(item -> allowed.add(DecisionAuthorityPolicy.Authority.valueOf(String.valueOf(item))));
            } catch (IllegalArgumentException exception) {
                throw invalid("unknown authority for " + classification);
            }
            result.put(classification, Set.copyOf(allowed));
        }
        if (values.size() != ContradictionClassifier.Classification.values().length) {
            throw invalid("autoResolution contains an unknown classification");
        }
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        try (InputStream input = DeterministicContradictionResolver.class.getClassLoader()
                .getResourceAsStream(RESOURCE)) {
            if (input == null) throw invalid("missing " + RESOURCE);
            Object value = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            return map(value, "root");
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load contradiction resolution policy", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String field) {
        if (!(value instanceof Map<?, ?>)) throw invalid(field + " must be an object");
        return (Map<String, Object>) value;
    }

    private static String required(Map<String, Object> value, String field) {
        Object result = value.get(field);
        if (result == null || result.toString().isBlank()) throw invalid(field + " is required");
        return result.toString();
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Invalid contradiction resolution policy: " + message);
    }

    public record Result(Outcome outcome, DecisionAuthorityPolicy.Verdict verdict, String ruleId,
                         DecisionAuthorityPolicy.Authority controllingAuthority,
                         List<String> controllingClaimIds, String reason) {
        public Result {
            controllingClaimIds = List.copyOf(controllingClaimIds);
        }
    }

    public enum Outcome { RESOLVED, OPEN, ESCALATE }
}
