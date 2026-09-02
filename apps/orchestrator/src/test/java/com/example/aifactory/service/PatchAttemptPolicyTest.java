package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchAttemptPolicyTest {
    private final PatchAttemptPolicy policy = new PatchAttemptPolicy();

    @Test
    void boundsRepairAttemptsPerProposalAndEscalatesTheThird() {
        PatchAttemptPolicy.State state = policy.initial();
        PatchAttemptPolicy.Decision first = policy.authorizeRepair(state, "proposal-1");
        PatchAttemptPolicy.Decision second = policy.authorizeRepair(first.state(), "proposal-1");
        PatchAttemptPolicy.Decision third = policy.authorizeRepair(second.state(), "proposal-1");

        assertThat(first.action()).isEqualTo(PatchAttemptPolicy.Action.ALLOW);
        assertThat(second.action()).isEqualTo(PatchAttemptPolicy.Action.ALLOW);
        assertThat(third.action()).isEqualTo(PatchAttemptPolicy.Action.ESCALATE);
        assertThat(third.state().escalated()).isTrue();
        assertThat(third.reason()).contains("repair attempt limit");
        assertThatThrownBy(() -> PatchAttemptPolicy.requireAllowed(
                third, PatchAttemptPolicy.Operation.REPAIR, "proposal-1", 3))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void boundsIntegrationAttemptsPerPlanAndEscalatesTheFourth() {
        PatchAttemptPolicy.State state = policy.initial();
        PatchAttemptPolicy.Decision decision = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            decision = policy.authorizeIntegration(state, "a".repeat(64));
            state = decision.state();
        }

        assertThat(decision.action()).isEqualTo(PatchAttemptPolicy.Action.ESCALATE);
        assertThat(decision.attempt()).isEqualTo(4);
        assertThat(decision.state().integrationAttempts()).isEqualTo(3);
        assertThat(decision.reason()).contains("integration attempt limit");
    }

    @Test
    void authorizationCannotBeReusedForAnotherTargetOrAttempt() {
        PatchAttemptPolicy.Decision allowed = policy.authorizeRepair(policy.initial(), "proposal-1");

        PatchAttemptPolicy.requireAllowed(
                allowed, PatchAttemptPolicy.Operation.REPAIR, "proposal-1", 1);
        assertThatThrownBy(() -> PatchAttemptPolicy.requireAllowed(
                allowed, PatchAttemptPolicy.Operation.REPAIR, "proposal-2", 1))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> PatchAttemptPolicy.requireAllowed(
                allowed, PatchAttemptPolicy.Operation.REPAIR, "proposal-1", 2))
                .isInstanceOf(SecurityException.class);
    }
}
