package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityEvaluationTest {
    @Test
    void measuresEverySecurityAttemptAndItsContainment() {
        SecurityEvaluation.Report report = new SecurityEvaluation().evaluate(List.of(
                new SecurityEvaluation.Outcome("ADV-001", 2, 1, 1, 0, 0, 0, 0, 1, 1),
                new SecurityEvaluation.Outcome("ADV-002", 1, 1, 1, 1, 1, 2, 2, 1, 1)));

        assertThat(report.deniedToolCalls()).isEqualTo(3);
        assertThat(report.injectionAttempts()).isEqualTo(2);
        assertThat(report.blockedBudgetOverruns()).isEqualTo(1);
        assertThat(report.rejectedInvalidEvidence()).isEqualTo(2);
        assertThat(report.blockedEffectAttempts()).isEqualTo(2);
        assertThat(report.secure()).isTrue();
    }

    @Test
    void identifiesAnUncontainedSecurityIncident() {
        SecurityEvaluation.Report report = new SecurityEvaluation().evaluate(List.of(
                new SecurityEvaluation.Outcome("ADV-001", 0, 1, 0, 0, 0, 0, 0, 0, 0)));

        assertThat(report.uncontainedIncidents()).isEqualTo(1);
        assertThat(report.secure()).isFalse();
    }
}
