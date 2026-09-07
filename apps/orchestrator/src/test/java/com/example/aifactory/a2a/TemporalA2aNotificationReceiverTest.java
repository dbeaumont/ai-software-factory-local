package com.example.aifactory.a2a;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TemporalA2aNotificationReceiverTest {

    @Test
    void validatesCorrelationAndSignalsEachExactTransitionOnce() {
        A2aTaskAssociationStore.Association association = association();
        A2aTaskAssociationStore associations = associations(association);
        AtomicBoolean signalled = new AtomicBoolean();
        A2aNotificationInbox inbox = new A2aNotificationInbox() {
            @Override public Admission admit(A2aTaskAssociationStore.Association ignored,
                                             A2aContracts.Notification notification,
                                             String digest, String json) {
                return new Admission(signalled.compareAndSet(false, true));
            }
            @Override public void markSignalled(String role, String task, long sequence, Instant at) { }
        };
        WorkflowClient client = mock(WorkflowClient.class);
        WorkflowStub workflow = mock(WorkflowStub.class);
        when(client.newUntypedWorkflowStub(eq("workflow-1"), eq(Optional.of("run-1")), any()))
                .thenReturn(workflow);
        TemporalA2aNotificationReceiver receiver = new TemporalA2aNotificationReceiver(
                associations, inbox, client, new ObjectMapper());
        A2aContracts.Notification notification = notification("context-1");

        receiver.receive(notification).toCompletableFuture().join();
        receiver.receive(notification).toCompletableFuture().join();

        verify(workflow, times(1)).signal(eq(TemporalA2aNotificationReceiver.SIGNAL_NAME), any());
        assertThatThrownBy(() -> receiver.receive(notification("wrong-context")).toCompletableFuture().join())
                .hasCauseInstanceOf(SecurityException.class);
    }

    private static A2aTaskAssociationStore associations(A2aTaskAssociationStore.Association association) {
        return new A2aTaskAssociationStore() {
            @Override public void prepareDelegation(A2aExecutionContext execution, DispatchIntent intent) {
                throw new AssertionError();
            }
            @Override public void record(A2aExecutionContext execution, String messageId, String cardDigest,
                                         String taskId, String contextId) { throw new AssertionError(); }
            @Override public Optional<Association> findByDelegation(String delegationId) { return Optional.empty(); }
            @Override public Optional<Association> findByMessageId(String role, String messageId) {
                return Optional.empty();
            }
            @Override public Optional<Association> findByA2aTaskId(String role, String taskId) {
                return role.equals(association.agentRole()) && taskId.equals(association.a2aTaskId())
                        ? Optional.of(association) : Optional.empty();
            }
        };
    }

    private static A2aTaskAssociationStore.Association association() {
        return new A2aTaskAssociationStore.Association(
                "delegation-1", "task-1", "attempt-1", "workflow-1", "run-1", "a".repeat(40),
                "message-1", "developer", "b".repeat(64), "agent-task-1", "context-1");
    }

    private static A2aContracts.Notification notification(String contextId) {
        String digest = "c".repeat(64);
        String uri = "evidence://task-1/attempt-1/agent-result/" + digest;
        A2aContracts.Part reference = new A2aContracts.Part(A2aMediaTypes.EVIDENCE_REFERENCE, null,
                java.util.Map.of("uri", uri, "digest", digest), URI.create(uri));
        return new A2aContracts.Notification("developer", "agent-task-1", contextId, 1,
                A2aContracts.TaskState.WORKING, Instant.parse("2026-09-06T12:00:00Z"),
                List.of(new A2aContracts.Artifact("artifact-1", "result", List.of(reference), java.util.Map.of())),
                java.util.Map.of());
    }
}
