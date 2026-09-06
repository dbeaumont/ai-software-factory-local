package com.example.aifactory.workflow.temporal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class A2aWorkflowHistoryGuardTest {
    @Test
    void continuesBeforeEitherHistoryLimitOrWhenTemporalSuggestsIt() {
        assertThat(A2aWorkflowHistoryGuard.exceeds(249, 1_048_575, false)).isFalse();
        assertThat(A2aWorkflowHistoryGuard.exceeds(250, 1, false)).isTrue();
        assertThat(A2aWorkflowHistoryGuard.exceeds(1, 1_048_576, false)).isTrue();
        assertThat(A2aWorkflowHistoryGuard.exceeds(1, 1, true)).isTrue();
    }
}
