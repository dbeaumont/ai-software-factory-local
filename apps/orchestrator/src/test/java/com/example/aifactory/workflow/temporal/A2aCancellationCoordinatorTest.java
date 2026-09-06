package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aMediaTypes;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class A2aCancellationCoordinatorTest {

    @Test
    void confirmsCancellationAndPreservesExistingEvidence() {
        A2aContracts.TaskSnapshot working = task(A2aContracts.TaskState.WORKING, 1, true);
        A2aContracts.TaskSnapshot canceled = task(A2aContracts.TaskState.CANCELED, 2, false);
        A2aCancellationCoordinator.Result result = new A2aCancellationCoordinator(new A2aTaskAwaiter()).cancel(
                "developer", working, query -> canceled, query -> canceled, Duration.ofSeconds(1));

        assertThat(result.status()).isEqualTo(A2aCancellationCoordinator.Status.CONFIRMED);
        assertThat(result.evidenceUris()).containsExactly("evidence://task-1/attempt-1/agent-result/" + "a".repeat(64));
    }

    @Test
    void unreachableAgentProducesAnExplicitReconciliationState() {
        A2aContracts.TaskSnapshot working = task(A2aContracts.TaskState.WORKING, 1, false);
        A2aCancellationCoordinator.Result result = new A2aCancellationCoordinator(new A2aTaskAwaiter()).cancel(
                "developer", working, query -> { throw new IllegalStateException("offline"); },
                query -> { throw new AssertionError(); }, Duration.ofSeconds(1));

        assertThat(result.status()).isEqualTo(A2aCancellationCoordinator.Status.RECONCILIATION_REQUIRED);
        assertThat(result.task()).isEqualTo(working);
    }

    private static A2aContracts.TaskSnapshot task(A2aContracts.TaskState state, long sequence, boolean artifact) {
        List<A2aContracts.Artifact> artifacts = artifact ? List.of(new A2aContracts.Artifact(
                "artifact-1", "result", List.of(new A2aContracts.Part(A2aMediaTypes.EVIDENCE_REFERENCE, null,
                Map.of("uri", "evidence://task-1/attempt-1/agent-result/" + "a".repeat(64)),
                URI.create("evidence://task-1/attempt-1/agent-result/" + "a".repeat(64)))), Map.of())) : List.of();
        return new A2aContracts.TaskSnapshot("agent-task-1", "context-1", state,
                Instant.parse("2026-09-06T12:00:00Z"), artifacts, Map.of("sequence", sequence));
    }
}
