package com.example.aifactory.workflow.temporal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemporalIdsTest {
    @Test
    void derivesStableDistinctWorkflowDelegationActivityAndEffectIds() {
        String workflow = TemporalIds.workflow("task-1", "attempt-1");
        String delegation = TemporalIds.delegation("task-1", "attempt-1", "code-1");
        String activity = TemporalIds.activity("task-1", "attempt-1", "code-1", "apply-patch", 0);
        String effect = TemporalIds.effectKey("task-1", "attempt-1", "code-1", "apply-patch", 0);

        assertThat(TemporalIds.workflow("task-1", "attempt-1")).isEqualTo(workflow);
        assertThat(workflow).isEqualTo("ai-factory/task-1/attempt-1");
        assertThat(workflow).isNotEqualTo(delegation).isNotEqualTo(activity).isNotEqualTo(effect);
        assertThat(TemporalIds.activity("task-1", "attempt-1", "code-1", "apply-patch", 1))
                .isNotEqualTo(activity);
        assertThat(DurableExecutionActivities.Metadata.deterministic(
                "task-1", "attempt-1", "a".repeat(40), "code-1", "apply-patch", 0).idempotencyKey())
                .isEqualTo(effect);
    }

    @Test
    void boundsLongIdentifiersAndRejectsInvalidComponents() {
        assertThat(TemporalIds.delegation("t".repeat(128), "a".repeat(128), "n".repeat(128)))
                .hasSizeLessThanOrEqualTo(200).matches("delegation-[0-9a-f]{64}");
        assertThat(TemporalIds.workflow("t".repeat(128), "a".repeat(128)))
                .hasSizeLessThanOrEqualTo(200).matches("ai-factory/[0-9a-f]{64}");
        assertThatThrownBy(() -> TemporalIds.activity("task-1", "attempt-1", "node", "apply patch", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
