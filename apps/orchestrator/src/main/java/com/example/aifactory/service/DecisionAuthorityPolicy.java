package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Host-owned precedence policy used when consolidating decisions from multiple authorities. */
@Component
public final class DecisionAuthorityPolicy {
    private static final String RESOURCE = "multiagents/policies/decision-authority-policy-v1.yaml";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");

    private final String policyId;
    private final String policyVersion;
    private final List<Authority> authorityOrder;
    private final Map<Authority, Integer> priority;

    public DecisionAuthorityPolicy() {
        Map<String, Object> root = load();
        policyVersion = required(root, "version");
        if (!"1".equals(policyVersion)) throw invalidPolicy("unsupported version " + policyVersion);
        policyId = required(root, "policyId");
        authorityOrder = parseOrder(root.get("authorityOrder"));
        if (!"ESCALATE".equals(required(root, "sameLevelConflict"))) {
            throw invalidPolicy("sameLevelConflict must be ESCALATE");
        }
        if (!"FORBIDDEN".equals(required(root, "lowerAuthorityOverride"))) {
            throw invalidPolicy("lowerAuthorityOverride must be FORBIDDEN");
        }
        priority = priorities(authorityOrder);
    }

    public String policyId() { return policyId; }

    public String policyVersion() { return policyVersion; }

    public List<Authority> authorityOrder() { return authorityOrder; }

    /**
     * Selects only the claims at the strongest authority present. Lower-authority claims are retained for
     * audit but cannot influence the verdict. A disagreement at the controlling level is never resolved
     * silently and must be escalated.
     */
    public Resolution resolve(List<Claim> suppliedClaims) {
        if (suppliedClaims == null || suppliedClaims.isEmpty()) {
            throw new IllegalArgumentException("At least one authority claim is required");
        }
        List<Claim> claims = suppliedClaims.stream().map(DecisionAuthorityPolicy::requireClaim)
                .sorted(Comparator.comparing(Claim::claimId)).toList();
        requireUniqueIds(claims);

        Authority controllingAuthority = claims.stream().map(Claim::authority)
                .min(Comparator.comparingInt(priority::get)).orElseThrow();
        List<Claim> controlling = claims.stream()
                .filter(claim -> claim.authority() == controllingAuthority).toList();
        List<String> controllingIds = controlling.stream().map(Claim::claimId).toList();
        List<String> ignoredIds = claims.stream().filter(claim -> claim.authority() != controllingAuthority)
                .map(Claim::claimId).toList();
        Set<Verdict> verdicts = new HashSet<>(controlling.stream().map(Claim::verdict).toList());

        if (verdicts.size() > 1) {
            return new Resolution(Status.ESCALATE, controllingAuthority, null,
                    controllingIds, ignoredIds, "conflicting claims at controlling authority");
        }
        return new Resolution(Status.DECIDED, controllingAuthority, verdicts.iterator().next(),
                controllingIds, ignoredIds, "strongest available authority selected");
    }

    private static Claim requireClaim(Claim claim) {
        if (claim == null || claim.authority() == null || claim.verdict() == null
                || claim.claimId() == null || !ID.matcher(claim.claimId()).matches()
                || claim.evidenceDigest() == null || !DIGEST.matcher(claim.evidenceDigest()).matches()) {
            throw new IllegalArgumentException("Authority claim is invalid");
        }
        return claim;
    }

    private static void requireUniqueIds(List<Claim> claims) {
        Set<String> ids = new HashSet<>();
        if (claims.stream().map(Claim::claimId).anyMatch(id -> !ids.add(id))) {
            throw new IllegalArgumentException("Authority claim identifiers must be unique");
        }
    }

    private static Map<Authority, Integer> priorities(List<Authority> order) {
        Map<Authority, Integer> result = new EnumMap<>(Authority.class);
        for (int index = 0; index < order.size(); index++) result.put(order.get(index), index);
        return Map.copyOf(result);
    }

    private static List<Authority> parseOrder(Object value) {
        if (!(value instanceof List<?> values)) throw invalidPolicy("authorityOrder must be a list");
        List<Authority> result = new ArrayList<>();
        try {
            values.forEach(item -> result.add(Authority.valueOf(String.valueOf(item))));
        } catch (IllegalArgumentException exception) {
            throw invalidPolicy("authorityOrder contains an unknown authority");
        }
        List<Authority> expected = List.of(Authority.DETERMINISTIC_GATE, Authority.POLICY,
                Authority.VERIFIED_EVIDENCE, Authority.SPECIALIST_CONSENSUS, Authority.SUPERVISOR);
        if (!result.equals(expected)) throw invalidPolicy("authorityOrder does not match the mandatory precedence");
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        try (InputStream input = DecisionAuthorityPolicy.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw invalidPolicy("missing " + RESOURCE);
            Object value = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            if (!(value instanceof Map<?, ?>)) throw invalidPolicy("root must be an object");
            return (Map<String, Object>) value;
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load decision authority policy", exception);
        }
    }

    private static String required(Map<String, Object> value, String field) {
        Object result = value.get(field);
        if (result == null || result.toString().isBlank()) throw invalidPolicy(field + " is required");
        return result.toString();
    }

    private static IllegalStateException invalidPolicy(String message) {
        return new IllegalStateException("Invalid decision authority policy: " + message);
    }

    public record Claim(String claimId, Authority authority, Verdict verdict, String evidenceDigest) {}

    public record Resolution(Status status, Authority controllingAuthority, Verdict verdict,
                             List<String> controllingClaimIds, List<String> ignoredClaimIds, String reason) {
        public Resolution {
            controllingClaimIds = List.copyOf(controllingClaimIds);
            ignoredClaimIds = List.copyOf(ignoredClaimIds);
        }
    }

    public enum Authority {
        DETERMINISTIC_GATE,
        POLICY,
        VERIFIED_EVIDENCE,
        SPECIALIST_CONSENSUS,
        SUPERVISOR
    }

    public enum Verdict { ALLOW, DENY }

    public enum Status { DECIDED, ESCALATE }
}
