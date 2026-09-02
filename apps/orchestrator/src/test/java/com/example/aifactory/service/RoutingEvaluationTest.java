package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingEvaluationTest {
    @Test
    void measuresPathAccuracyAndSpecialistSelectionPrecisionAndRecall() {
        RoutingEvaluation.Report report = new RoutingEvaluation().evaluate(List.of(
                new RoutingEvaluation.Case("SHORT-001", RoutingEvaluation.Path.SHORT_CODE_PATH,
                        RoutingEvaluation.Path.SHORT_CODE_PATH,
                        Set.of("developer"), Set.of("developer")),
                new RoutingEvaluation.Case("HIER-001", RoutingEvaluation.Path.HIERARCHICAL_PATH,
                        RoutingEvaluation.Path.HIERARCHICAL_PATH,
                        Set.of("architecture-agent", "test-agent", "security-agent"),
                        Set.of("architecture-agent", "test-agent", "security-agent", "code-agent")),
                new RoutingEvaluation.Case("HIER-002", RoutingEvaluation.Path.HIERARCHICAL_PATH,
                        RoutingEvaluation.Path.SHORT_CODE_PATH,
                        Set.of("architecture-agent", "security-agent"), Set.of("architecture-agent"))));

        assertThat(report.pathAccuracy()).isEqualTo(2.0 / 3.0);
        assertThat(report.specialistPrecision()).isEqualTo(5.0 / 6.0);
        assertThat(report.specialistRecall()).isEqualTo(5.0 / 6.0);
        assertThat(report.cases()).isEqualTo(3);
    }
}
