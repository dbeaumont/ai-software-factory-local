package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable host policy bounding patch repair and integration attempts before human escalation. */
@Component
public final class PatchAttemptPolicy {
    public static final int MAX_REPAIRS_PER_PROPOSAL = 2;
    public static final int MAX_INTEGRATION_ATTEMPTS = 3;

    public State initial() {
        return new State(0, Map.of(), false, null);
    }

    public Decision authorizeIntegration(State current, String planDigest) {
        requireState(current);
        requireTarget(planDigest);
        int attempt = current.integrationAttempts() + 1;
        if (current.escalated() || attempt > MAX_INTEGRATION_ATTEMPTS) {
            return escalate(current, Operation.INTEGRATION, planDigest, attempt,
                    "integration attempt limit exceeded");
        }
        State next = new State(attempt, current.repairAttempts(), false, null);
        return new Decision(Action.ALLOW, Operation.INTEGRATION, planDigest, attempt, next,
                "integration attempt authorized");
    }

    public Decision authorizeRepair(State current, String proposalId) {
        requireState(current);
        requireTarget(proposalId);
        int attempt = current.repairAttempts().getOrDefault(proposalId, 0) + 1;
        if (current.escalated() || attempt > MAX_REPAIRS_PER_PROPOSAL) {
            return escalate(current, Operation.REPAIR, proposalId, attempt,
                    "repair attempt limit exceeded");
        }
        Map<String, Integer> repairs = new LinkedHashMap<>(current.repairAttempts());
        repairs.put(proposalId, attempt);
        State next = new State(current.integrationAttempts(), repairs, false, null);
        return new Decision(Action.ALLOW, Operation.REPAIR, proposalId, attempt, next,
                "targeted repair attempt authorized");
    }

    public static void requireAllowed(Decision decision, Operation operation, String target, int attempt) {
        if (decision == null || decision.action() != Action.ALLOW || decision.operation() != operation
                || !target.equals(decision.targetId()) || decision.attempt() != attempt
                || decision.state() == null || decision.state().escalated()) {
            throw new SecurityException("Patch attempt is not authorized by the host policy");
        }
        int recorded = operation == Operation.INTEGRATION
                ? decision.state().integrationAttempts()
                : decision.state().repairAttempts().getOrDefault(target, 0);
        int maximum = operation == Operation.INTEGRATION
                ? MAX_INTEGRATION_ATTEMPTS : MAX_REPAIRS_PER_PROPOSAL;
        if (recorded != attempt || attempt < 1 || attempt > maximum) {
            throw new SecurityException("Patch attempt authorization is inconsistent or exhausted");
        }
    }

    private static Decision escalate(State current, Operation operation, String target, int attempt, String reason) {
        State escalated = new State(current.integrationAttempts(), current.repairAttempts(), true, reason);
        return new Decision(Action.ESCALATE, operation, target, attempt, escalated, reason);
    }

    private static void requireState(State state) {
        if (state == null || state.integrationAttempts() < 0
                || state.repairAttempts().values().stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("Patch attempt state is invalid");
        }
    }

    private static void requireTarget(String target) {
        if (target == null || target.isBlank() || target.length() > 128) {
            throw new IllegalArgumentException("Patch attempt target is invalid");
        }
    }

    public record State(int integrationAttempts, Map<String, Integer> repairAttempts,
                        boolean escalated, String escalationReason) {
        public State {
            repairAttempts = repairAttempts == null ? Map.of() : Map.copyOf(repairAttempts);
            if (!escalated && escalationReason != null || escalated && (escalationReason == null
                    || escalationReason.isBlank())) {
                throw new IllegalArgumentException("Patch escalation state is inconsistent");
            }
        }
    }

    public record Decision(Action action, Operation operation, String targetId, int attempt,
                           State state, String reason) {}

    public enum Action { ALLOW, ESCALATE }

    public enum Operation { REPAIR, INTEGRATION }
}
