package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aClient;
import com.example.aifactory.a2a.A2aContractMapping;
import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aExecutionContext;
import com.example.aifactory.a2a.A2aMediaTypes;
import com.example.aifactory.a2a.A2aTaskAssociationStore;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class A2aTemporalAmbiguousDispatchTest {
    private static final String QUEUE = "a2a-ambiguous-dispatch";

    @Test
    void activityRetryReconcilesALostAcknowledgementWithoutSendingTwice() {
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger gets = new AtomicInteger();
        A2aContracts.TaskSnapshot remote = new A2aContracts.TaskSnapshot(
                "remote-task-1", "remote-context-1", A2aContracts.TaskState.COMPLETED,
                Instant.parse("2026-09-06T12:00:00Z"), List.of(), Map.of("sequence", 1));
        A2aClient client = new A2aClient() {
            @Override public java.util.concurrent.CompletionStage<A2aContracts.TaskSnapshot> send(
                    A2aContracts.SendCommand command) {
                sends.incrementAndGet();
                return CompletableFuture.completedFuture(remote);
            }
            @Override public java.util.concurrent.CompletionStage<A2aContracts.TaskSnapshot> getTask(
                    A2aContracts.TaskQuery query) {
                gets.incrementAndGet();
                return CompletableFuture.completedFuture(remote);
            }
            @Override public java.util.concurrent.CompletionStage<A2aContracts.TaskSnapshot> cancelTask(
                    A2aContracts.TaskQuery query) {
                return CompletableFuture.completedFuture(remote);
            }
            @Override public java.util.concurrent.CompletionStage<Optional<A2aContracts.TaskSnapshot>>
            findTaskByMessageId(String role, String messageId) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
        };
        AtomicReference<A2aTaskAssociationStore.Association> stored = new AtomicReference<>();
        AtomicInteger records = new AtomicInteger();
        A2aTaskAssociationStore associations = new A2aTaskAssociationStore() {
            @Override public void record(A2aExecutionContext execution, String messageId, String cardDigest,
                                         String taskId, String contextId) {
                stored.set(new Association(execution.delegationId(), execution.taskId(), execution.attemptId(),
                        execution.workflowId(), execution.workflowRunId(), execution.sourceCommit(), messageId,
                        execution.agentRole(), cardDigest, taskId, contextId));
                if (records.incrementAndGet() == 1) {
                    throw new IllegalStateException("simulated lost activity acknowledgement after durable write");
                }
            }
            @Override public Optional<Association> findByDelegation(String delegationId) {
                return Optional.ofNullable(stored.get());
            }
            @Override public Optional<Association> findByMessageId(String agentRole, String messageId) {
                return Optional.empty();
            }
            @Override public Optional<Association> findByA2aTaskId(String agentRole, String taskId) {
                return Optional.empty();
            }
        };
        A2aActivitiesImpl activities = new A2aActivitiesImpl(
                role -> CompletableFuture.failedStage(new AssertionError("card resolution is not expected")),
                client, new A2aContractMapping(new ObjectMapper()), associations);

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker(QUEUE);
            worker.registerWorkflowImplementationTypes(HarnessImpl.class);
            worker.registerActivitiesImplementations(activities);
            environment.start();
            Harness workflow = environment.getWorkflowClient().newWorkflowStub(Harness.class,
                    WorkflowOptions.newBuilder().setWorkflowId("a2a-ambiguous-dispatch-1")
                            .setTaskQueue(QUEUE).build());

            assertThat(workflow.run(request())).isEqualTo("remote-task-1:COMPLETED");
        }

        assertThat(sends).as("the remote side effect is never duplicated").hasValue(1);
        assertThat(records).as("the durable correlation is written once").hasValue(1);
        assertThat(gets).as("the retry reconciles from the persisted association").hasValue(1);
    }

    private static A2aActivities.DispatchRequest request() {
        A2aExecutionContext execution = new A2aExecutionContext(
                "1", "task-1", "attempt-1", "workflow-1", "run-1", "customer-api",
                "a".repeat(40), "delegation-1", null, "developer", List.of("c".repeat(64)));
        A2aContracts.SendCommand command = new A2aContracts.SendCommand(
                "developer", "developer.code-task-v1", "message-1", null, null,
                List.of(new A2aContracts.Part(
                        A2aMediaTypes.JSON, null, Map.of("instruction", "change"), null)),
                Map.of(), true);
        return new A2aActivities.DispatchRequest(execution, "b".repeat(64), command);
    }

    @WorkflowInterface
    public interface Harness {
        @WorkflowMethod String run(A2aActivities.DispatchRequest request);
    }

    public static final class HarnessImpl implements Harness {
        private final A2aActivities.ReconcileDispatch reconcile = Workflow.newActivityStub(
                A2aActivities.ReconcileDispatch.class,
                TemporalActivityPolicies.forKind(TemporalActivityPolicies.Kind.A2A_RECONCILE));

        @Override public String run(A2aActivities.DispatchRequest request) {
            A2aContracts.TaskSnapshot result = reconcile.reconcileDispatch(request);
            return result.taskId() + ':' + result.state();
        }
    }
}
