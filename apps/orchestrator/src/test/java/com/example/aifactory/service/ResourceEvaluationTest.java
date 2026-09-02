package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceEvaluationTest {
    @Test
    void measuresModelSandboxElapsedAndHumanResources() {
        List<ResourceEvaluation.Usage> usages = IntStream.rangeClosed(1, 20)
                .mapToObj(index -> new ResourceEvaluation.Usage("CASE-" + index,
                        100L * index, 200L * index, 1_000L * index,
                        300L * index, 400L * index)).toList();

        ResourceEvaluation.Report report = new ResourceEvaluation().evaluate(usages);

        assertThat(report.totalTokens()).isEqualTo(21_000);
        assertThat(report.totalCostMicros()).isEqualTo(42_000);
        assertThat(report.averageDurationMillis()).isEqualTo(10_500);
        assertThat(report.totalSandboxComputeMillis()).isEqualTo(63_000);
        assertThat(report.totalHumanMillis()).isEqualTo(84_000);
        assertThat(report.p95DurationMillis()).isEqualTo(19_000);
    }
}
