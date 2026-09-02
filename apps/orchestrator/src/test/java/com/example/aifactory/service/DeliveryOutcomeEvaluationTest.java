package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryOutcomeEvaluationTest {
    @Test
    void measuresEveryStageOfTheDeliveryQualityFunnel() {
        DeliveryOutcomeEvaluation.Report report = new DeliveryOutcomeEvaluation().evaluate(List.of(
                new DeliveryOutcomeEvaluation.Outcome("CASE-1", true, 0, true, true, true),
                new DeliveryOutcomeEvaluation.Outcome("CASE-2", false, 2, true, false, false),
                new DeliveryOutcomeEvaluation.Outcome("CASE-3", true, 1, false, false, false)));

        assertThat(report.firstPatchSuccessRate()).isEqualTo(2.0 / 3.0);
        assertThat(report.averageRepairs()).isEqualTo(1.0);
        assertThat(report.testSuccessRate()).isEqualTo(2.0 / 3.0);
        assertThat(report.reviewAcceptanceRate()).isEqualTo(1.0 / 3.0);
        assertThat(report.humanAcceptanceRate()).isEqualTo(1.0 / 3.0);
    }
}
