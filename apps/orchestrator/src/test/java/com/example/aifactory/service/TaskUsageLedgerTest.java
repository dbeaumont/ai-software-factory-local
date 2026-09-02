package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskUsageLedgerTest {
    @Test
    void accumulatesUsageAcrossAttemptsOfTheSameTask() {
        TaskUsageLedger ledger = ledger();

        ledger.consume("task-1", "attempt-1", new TaskUsageLedger.Delta(10, 20, 30, 1, 2));
        TaskUsageLedger.Snapshot result = ledger.consume(
                "task-1", "attempt-2", new TaskUsageLedger.Delta(11, 21, 31, 2, 3));

        assertThat(result).isEqualTo(new TaskUsageLedger.Snapshot(21, 41, 61, 3, 5, "attempt-2"));
        assertThat(ledger.snapshot("other-task")).isEqualTo(TaskUsageLedger.Snapshot.zero());
    }

    @Test
    void rejectsEachCumulativeQuotaWithoutPartiallyRecordingTheDelta() {
        assertQuota("input_tokens", new TaskUsageLedger.Delta(120_001, 0, 0, 0, 0));
        assertQuota("output_tokens", new TaskUsageLedger.Delta(0, 40_001, 0, 0, 0));
        assertQuota("cost_micros", new TaskUsageLedger.Delta(0, 0, 80_000_001, 0, 0));
        assertQuota("turns", new TaskUsageLedger.Delta(0, 0, 0, 61, 0));
        assertQuota("mcp_calls", new TaskUsageLedger.Delta(0, 0, 0, 0, 209));
    }

    @Test
    void standardWorkCannotConsumeTheFinalizationReserve() {
        TaskUsageLedger ledger = ledger();
        ledger.consume("task-1", "attempt-1", TaskUsageLedger.Lane.STANDARD,
                new TaskUsageLedger.Delta(110_000, 35_000, 70_000_000, 54, 176));

        assertThatThrownBy(() -> ledger.consume("task-1", "attempt-1", TaskUsageLedger.Lane.STANDARD,
                new TaskUsageLedger.Delta(0, 0, 0, 0, 1)))
                .isInstanceOf(TaskUsageLedger.QuotaExceededException.class);

        TaskUsageLedger.Snapshot complete = ledger.consume("task-1", "attempt-2",
                TaskUsageLedger.Lane.FINALIZATION,
                new TaskUsageLedger.Delta(10_000, 5_000, 10_000_000, 6, 32));
        assertThat(complete).isEqualTo(
                new TaskUsageLedger.Snapshot(120_000, 40_000, 80_000_000, 60, 208, "attempt-2"));
    }

    private static void assertQuota(String quota, TaskUsageLedger.Delta delta) {
        TaskUsageLedger ledger = ledger();
        ledger.consume("task-1", "attempt-1", new TaskUsageLedger.Delta(1, 1, 1, 1, 1));

        assertThatThrownBy(() -> ledger.consume("task-1", "attempt-2", delta))
                .isInstanceOf(TaskUsageLedger.QuotaExceededException.class)
                .extracting(error -> ((TaskUsageLedger.QuotaExceededException) error).quota())
                .isEqualTo(quota);
        assertThatThrownBy(() -> ledger.consume("task-1", "attempt-2", delta))
                .extracting(error -> ((TaskUsageLedger.QuotaExceededException) error).stopCondition())
                .isEqualTo(AgentToolLoop.StopCondition.BUDGET_EXHAUSTED);
        assertThat(ledger.snapshot("task-1"))
                .isEqualTo(new TaskUsageLedger.Snapshot(1, 1, 1, 1, 1, "attempt-1"));
    }

    private static TaskUsageLedger ledger() {
        return new TaskUsageLedger(new HierarchicalBudgetPolicy());
    }
}
