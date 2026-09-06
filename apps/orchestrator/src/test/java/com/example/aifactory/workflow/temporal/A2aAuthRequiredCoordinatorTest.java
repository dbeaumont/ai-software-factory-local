package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aAuthGrant;
import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aExecutionContext;
import com.example.aifactory.a2a.A2aMediaTypes;
import com.example.aifactory.a2a.A2aTaskAssociationStore;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aAuthRequiredCoordinatorTest {
    @Test
    void resolvesCredentialOnlyInsideActivityAndClearsItAfterUse() throws Exception {
        A2aExecutionContext execution = execution();
        A2aContracts.TaskSnapshot current = new A2aContracts.TaskSnapshot("a2a-task-1", "context-1",
                A2aContracts.TaskState.AUTH_REQUIRED, Instant.EPOCH, List.of(), Map.of("sequence", 4L));
        A2aAuthGrant grant = new A2aAuthGrant("grant-1", "developer", "a2a-task-1", "context-1",
                "developer.code-task-v1", Instant.parse("2030-01-01T00:00:00Z"), "b".repeat(64));
        A2aContracts.Part evidence = evidence();
        char[] secret = "short-lived-token-value".toCharArray();
        AtomicReference<String> received = new AtomicReference<>();
        A2aAuthActivitiesImpl activity = new A2aAuthActivitiesImpl(associations(execution), ignored -> secret,
                (command, token) -> {
                    received.set(new String(token));
                    return CompletableFuture.completedFuture(new A2aContracts.TaskSnapshot(
                            "a2a-task-1", "context-1", A2aContracts.TaskState.WORKING,
                            Instant.EPOCH, List.of(), Map.of("sequence", 5L)));
                });

        A2aContracts.TaskSnapshot result = new A2aAuthRequiredCoordinator().resume(execution, current,
                new A2aAuthRequiredCoordinator.Decision("david", grant, evidence), Map.of(), activity);

        assertThat(result.state()).isEqualTo(A2aContracts.TaskState.WORKING);
        assertThat(received).hasValue("short-lived-token-value");
        assertThat(secret).containsOnly('\0');
        AtomicReference<A2aAuthActivities.ResumeRequest> captured = new AtomicReference<>();
        new A2aAuthRequiredCoordinator().resume(execution, current,
                new A2aAuthRequiredCoordinator.Decision("david", grant, evidence), Map.of(),
                request -> { captured.set(request); return result; });
        String historyPayload = new ObjectMapper().writeValueAsString(captured.get());
        assertThat(historyPayload).contains("grant-1", "bindingDigest")
                .doesNotContain("short-lived-token-value", "bearerToken", "accessToken");
    }

    @Test
    void rejectsExpiredGrantBeforeCredentialLookup() {
        A2aExecutionContext execution = execution();
        A2aAuthGrant expired = new A2aAuthGrant("grant-expired", "developer", "a2a-task-1", "context-1",
                "developer.code-task-v1", Instant.EPOCH, "b".repeat(64));
        A2aContracts.SendCommand command = new A2aContracts.SendCommand("developer", "developer.code-task-v1",
                "message-1", "a2a-task-1", "context-1", List.of(evidence()), Map.of(), true);
        A2aAuthActivitiesImpl activity = new A2aAuthActivitiesImpl(associations(execution),
                ignored -> { throw new AssertionError("vault must not be called"); },
                (ignored, token) -> { throw new AssertionError("sender must not be called"); });

        assertThatThrownBy(() -> activity.resumeAuth(new A2aAuthActivities.ResumeRequest(
                execution, command, expired))).isInstanceOf(SecurityException.class);
    }

    private static A2aTaskAssociationStore associations(A2aExecutionContext execution) {
        A2aTaskAssociationStore.Association association = new A2aTaskAssociationStore.Association(
                execution.delegationId(), execution.taskId(), execution.attemptId(), execution.workflowId(),
                execution.workflowRunId(), execution.sourceCommit(), "initial-message", execution.agentRole(),
                "c".repeat(64), "a2a-task-1", "context-1");
        return new A2aTaskAssociationStore() {
            @Override public void record(A2aExecutionContext ignored, String messageId, String cardDigest,
                                         String taskId, String contextId) { }
            @Override public Optional<Association> findByDelegation(String delegationId) {
                return Optional.of(association);
            }
            @Override public Optional<Association> findByMessageId(String role, String messageId) {
                return Optional.empty();
            }
            @Override public Optional<Association> findByA2aTaskId(String role, String taskId) {
                return Optional.of(association);
            }
        };
    }

    private static A2aExecutionContext execution() {
        return new A2aExecutionContext("1", "root-task", "attempt-1", "workflow-1", "run-1", "repository-1",
                "0".repeat(40), "delegation-1", null, "developer", List.of("a".repeat(64)));
    }

    private static A2aContracts.Part evidence() {
        String uri = "evidence://root-task/attempt-1/auth/decision.json";
        return new A2aContracts.Part(A2aMediaTypes.EVIDENCE_REFERENCE, null,
                Map.of("uri", uri, "digest", "d".repeat(64), "contract", "auth-decision-v1"), URI.create(uri));
    }
}
