package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aExecutionContext;
import com.example.aifactory.a2a.A2aMediaTypes;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aInputRequiredCoordinatorTest {
    private final A2aInputRequiredCoordinator coordinator = new A2aInputRequiredCoordinator();
    private final A2aExecutionContext execution = new A2aExecutionContext("1", "root-task", "attempt-1",
            "workflow-1", "run-1", "repository-1", "0".repeat(40), "delegation-1", null,
            "developer", List.of("a".repeat(64)));
    private final A2aContracts.TaskSnapshot inputRequired = new A2aContracts.TaskSnapshot(
            "a2a-task-1", "a2a-context-1", A2aContracts.TaskState.INPUT_REQUIRED, Instant.EPOCH,
            List.of(), Map.of("sequence", 2L));
    private final A2aContracts.Part evidence = new A2aContracts.Part(A2aMediaTypes.EVIDENCE_REFERENCE, null,
            Map.of("schema_version", "1", "uri", "evidence://root-task/attempt-1/gate/decision.json",
                    "digest", "b".repeat(64), "contract", "gate-decision-v1"),
            URI.create("evidence://root-task/attempt-1/gate/decision.json"));

    @Test
    void resumesFromAuthorizedEvidenceWithoutChangingTaskCorrelation() {
        AtomicReference<A2aActivities.ContinuationRequest> captured = new AtomicReference<>();
        A2aContracts.TaskSnapshot resumed = new A2aContracts.TaskSnapshot("a2a-task-1", "a2a-context-1",
                A2aContracts.TaskState.WORKING, Instant.EPOCH, List.of(), Map.of("sequence", 3L));

        A2aContracts.TaskSnapshot result = coordinator.resume(execution, "developer.code-task-v1", inputRequired,
                new A2aInputRequiredCoordinator.Decision(true, "david", evidence), Map.of("gate", "quality"),
                request -> { captured.set(request); return resumed; });

        assertThat(result).isEqualTo(resumed);
        A2aContracts.SendCommand command = captured.get().command();
        assertThat(command.taskId()).isEqualTo("a2a-task-1");
        assertThat(command.contextId()).isEqualTo("a2a-context-1");
        assertThat(command.parts()).containsExactly(evidence);
        assertThat(command.metadata()).containsEntry("gateActor", "david")
                .containsEntry("continuationSequence", 3L);
        assertThat(command.messageId()).isEqualTo(TemporalIds.sha256(String.join("\n", "delegation-1",
                "a2a-task-1", "3", "b".repeat(64))));
    }

    @Test
    void rejectsMissingApprovalOrEvidence() {
        assertThatThrownBy(() -> coordinator.resume(execution, "developer.code-task-v1", inputRequired,
                new A2aInputRequiredCoordinator.Decision(false, "david", evidence), Map.of(), request -> inputRequired))
                .isInstanceOf(SecurityException.class);
    }
}
