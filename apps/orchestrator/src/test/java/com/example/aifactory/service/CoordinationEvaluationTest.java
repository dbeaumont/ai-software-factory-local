package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoordinationEvaluationTest {
    @Test
    void measuresScopeQualityPreventedCollisionsAndDetectedContradictions() {
        CoordinationEvaluation.Report report = new CoordinationEvaluation().evaluate(List.of(
                new CoordinationEvaluation.Case("HIER-001", 3, 3, 2, 2, 1, 1),
                new CoordinationEvaluation.Case("HIER-002", 2, 1, 1, 1, 2, 1)));

        assertThat(report.validScopeRate()).isEqualTo(0.8);
        assertThat(report.collisionPreventionRate()).isEqualTo(1.0);
        assertThat(report.contradictionRecall()).isEqualTo(2.0 / 3.0);
        assertThat(report.cases()).isEqualTo(2);
    }

    @Test
    void refusesImpossibleCountersInsteadOfMaskingCampaignErrors() {
        assertThatThrownBy(() -> new CoordinationEvaluation.Case(
                "HIER-001", 1, 2, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
