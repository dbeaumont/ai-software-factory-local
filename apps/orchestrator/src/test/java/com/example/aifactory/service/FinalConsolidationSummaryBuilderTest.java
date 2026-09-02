package com.example.aifactory.service;

import com.example.aifactory.workflow.ArbitrationJournal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinalConsolidationSummaryBuilderTest {
    private final FinalConsolidationSummaryBuilder builder = new FinalConsolidationSummaryBuilder();

    @Test
    void producesAStableOrderedSummaryWithDecisionsRisksAndHumanPoints() {
        ArbitrationJournal.Entry first = entry("arbitration-b", "contradiction-b", "b".repeat(64));
        ArbitrationJournal.Entry second = entry("arbitration-a", "contradiction-a", "a".repeat(64));
        var high = new FinalConsolidationSummaryBuilder.ResidualRisk("risk-high",
                FinalConsolidationSummaryBuilder.Severity.HIGH, "High residual", "Human acceptance",
                List.of("evidence://task-1/security"));
        var low = new FinalConsolidationSummaryBuilder.ResidualRisk("risk-low",
                FinalConsolidationSummaryBuilder.Severity.LOW, "Low residual", "Monitor",
                List.of("evidence://task-1/tests"));
        var human = new FinalConsolidationSummaryBuilder.HumanPoint("decision-1", "contradiction-b",
                HumanDecisionEscalator.DecisionDomain.PRODUCT, "d".repeat(64), "Choose behavior");

        var forward = builder.build(request(List.of(first, second), List.of(low, high), List.of(human)));
        var reverse = builder.build(request(List.of(second, first), List.of(high, low), List.of(human)));

        assertThat(forward).isEqualTo(reverse);
        assertThat(forward.status()).isEqualTo(FinalConsolidationSummaryBuilder.Status.HUMAN_DECISION_REQUIRED);
        assertThat(forward.decisions()).extracting(FinalConsolidationSummaryBuilder.DecisionSummary::contradictionId)
                .containsExactly("contradiction-a", "contradiction-b");
        assertThat(forward.residualRisks()).extracting(FinalConsolidationSummaryBuilder.ResidualRisk::riskId)
                .containsExactly("risk-high", "risk-low");
        assertThat(forward.digest()).hasSize(64);
    }

    @Test
    void isReadyForIndependentReviewWhenNoHumanPointRemains() {
        var summary = builder.build(request(List.of(entry("arbitration-a", "contradiction-a", "a".repeat(64))),
                List.of(), List.of()));

        assertThat(summary.status()).isEqualTo(FinalConsolidationSummaryBuilder.Status.READY_FOR_INDEPENDENT_REVIEW);
    }

    @Test
    void rejectsAnArbitrationFromAnotherAttempt() {
        ArbitrationJournal.Entry foreign = new ArbitrationJournal.Entry("arbitration-a", "task-1", "attempt-2",
                "a".repeat(40), "contradiction-a", "rule", "1", "ALLOW", "policy",
                ArbitrationJournal.AuthorType.POLICY,
                List.of(new ArbitrationJournal.InputReference("input", "a".repeat(64))),
                List.of(new ArbitrationJournal.EvidenceReference("evidence://task-1/a", "b".repeat(64))),
                "a".repeat(64), "2026-09-02T20:00:00Z");

        assertThatThrownBy(() -> builder.build(request(List.of(foreign), List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("lineage");
    }

    private static FinalConsolidationSummaryBuilder.Request request(List<ArbitrationJournal.Entry> entries,
            List<FinalConsolidationSummaryBuilder.ResidualRisk> risks,
            List<FinalConsolidationSummaryBuilder.HumanPoint> points) {
        return new FinalConsolidationSummaryBuilder.Request("task-1", "attempt-1", "a".repeat(40),
                entries, risks, points, "2026-09-02T21:00:00Z");
    }

    private static ArbitrationJournal.Entry entry(String id, String contradictionId, String recordDigest) {
        return new ArbitrationJournal.Entry(id, "task-1", "attempt-1", "a".repeat(40), contradictionId,
                "factual.policy_wins", "1", "ALLOW", "policy", ArbitrationJournal.AuthorType.POLICY,
                List.of(new ArbitrationJournal.InputReference("input-" + contradictionId, "a".repeat(64))),
                List.of(new ArbitrationJournal.EvidenceReference("evidence://task-1/" + contradictionId,
                        "b".repeat(64))), recordDigest, "2026-09-02T20:00:00Z");
    }
}
