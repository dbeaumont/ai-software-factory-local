package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Host gate preventing a Supervisor proposal from overriding deterministic assurance failures. */
@Component
public final class SupervisorConsolidationGuard {
    private static final Pattern DIGEST = Pattern.compile("[a-f0-9]{64}");

    public void enforce(JsonNode supervisorDecision, GateBundle bundle) {
        requireBundle(bundle);
        if (supervisorDecision == null || !supervisorDecision.path("action").isTextual()) {
            throw new IllegalArgumentException("Supervisor consolidation decision is missing its action");
        }
        String action = supervisorDecision.path("action").asText();
        List<GateResult> blocking = bundle.results().stream()
                .filter(result -> result.status() != Status.PASSED).toList();
        if (!blocking.isEmpty() && "CONSOLIDATE".equals(action)) {
            throw new SecurityException("Supervisor cannot consolidate over deterministic gate failures: "
                    + blocking.stream().map(result -> result.gate().name()).sorted().toList());
        }
    }

    private static void requireBundle(GateBundle bundle) {
        if (bundle == null || bundle.results() == null || bundle.results().size() != Gate.values().length) {
            throw new IllegalArgumentException("All deterministic consolidation gates are required");
        }
        Set<Gate> gates = EnumSet.noneOf(Gate.class);
        Set<String> evidence = new HashSet<>();
        for (GateResult result : bundle.results()) {
            if (result == null || result.gate() == null || result.status() == null
                    || result.evidenceDigest() == null || !DIGEST.matcher(result.evidenceDigest()).matches()
                    || !gates.add(result.gate()) || !evidence.add(result.evidenceDigest())) {
                throw new IllegalArgumentException("Deterministic consolidation gate is invalid or duplicated");
            }
        }
        if (!gates.equals(EnumSet.allOf(Gate.class))) {
            throw new IllegalArgumentException("All deterministic consolidation gates are required");
        }
    }

    public record GateBundle(List<GateResult> results) {
        public GateBundle {
            results = results == null ? null : List.copyOf(results);
        }
    }

    public record GateResult(Gate gate, Status status, String evidenceDigest) {}

    public enum Gate { TESTS, QUALITY, SECURITY, POLICY }

    public enum Status { PASSED, FAILED, INDETERMINATE }
}
