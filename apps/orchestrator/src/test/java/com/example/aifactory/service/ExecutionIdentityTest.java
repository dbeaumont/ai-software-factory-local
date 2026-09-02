package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionIdentityTest {
    @Test
    void derivesStableBoundedCorrelationIdentifiers() {
        ExecutionIdentity first = ExecutionIdentity.deterministic("task-1", "run-1", "code-1", "agent-1");
        ExecutionIdentity second = ExecutionIdentity.deterministic("task-1", "run-1", "code-1", "agent-1");

        assertThat(first).isEqualTo(second);
        assertThat(first.traceId()).matches("[0-9a-f]{32}");
        assertThat(first.runId()).isEqualTo("run-1");
        assertThat(first.delegationId()).isEqualTo("code-1");
        assertThat(first.agentRunId()).matches("agent-[0-9a-f]{24}");
    }

    @Test
    void rejectsMalformedCorrelationIdentifiers() {
        assertThatThrownBy(() -> new ExecutionIdentity("invalid", "run-1", "code-1", "agent-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
