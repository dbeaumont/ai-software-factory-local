package com.example.aifactory.service;

import java.util.List;
import java.util.Map;

/** Immutable host decision retained for routing audits and offline evaluation. */
public record RoutingDecision(String decisionId, String policyId, String policyVersion, String taskId,
                              String sourceCommit, String requestedMode, String effectiveMode,
                              Map<String, String> normalizedInputs, String matchedRule, String selectedPath,
                              List<String> reasons, List<String> agents, String humanGate) {
    public RoutingDecision {
        normalizedInputs = Map.copyOf(normalizedInputs);
        reasons = List.copyOf(reasons);
        agents = List.copyOf(agents);
    }
}
