package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aTaskAwaiterTest {

    @Test
    void workflowAwaitsASignalWithoutBlockingAndDeduplicatesItsSequence() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("a2a-await-test");
            worker.registerWorkflowImplementationTypes(HarnessImpl.class);
            environment.start();
            Harness workflow = environment.getWorkflowClient().newWorkflowStub(Harness.class,
                    WorkflowOptions.newBuilder().setWorkflowId("a2a-await-1").setTaskQueue("a2a-await-test").build());
            var execution = WorkflowClient.start(workflow::run, "agent-task-1");

            workflow.update(notification(1, A2aContracts.TaskState.WORKING));

            assertThat(environment.getWorkflowClient().newUntypedWorkflowStub(execution.getWorkflowId())
                    .getResult(String.class)).isEqualTo("WORKING:1");
        }
    }

    @Test
    void divergentSameSequenceIsRejectedBeforeItCanChangeWorkflowState() {
        A2aTaskAwaiter awaiter = new A2aTaskAwaiter();
        awaiter.accept(notification(2, A2aContracts.TaskState.WORKING));
        awaiter.accept(notification(2, A2aContracts.TaskState.WORKING));
        assertThatThrownBy(() -> awaiter.accept(notification(2, A2aContracts.TaskState.COMPLETED)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void timerFetchesAndAppliesEveryMissingTransitionInSequenceOrder() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("a2a-reconcile-test");
            worker.registerWorkflowImplementationTypes(ReconcileHarnessImpl.class);
            worker.registerActivitiesImplementations((A2aActivities.GetTask) query -> completedSnapshot());
            environment.start();
            ReconcileHarness workflow = environment.getWorkflowClient().newWorkflowStub(ReconcileHarness.class,
                    WorkflowOptions.newBuilder().setWorkflowId("a2a-reconcile-1")
                            .setTaskQueue("a2a-reconcile-test").build());

            assertThat(workflow.run()).isEqualTo("COMPLETED:2");
        }
    }

    private static A2aContracts.Notification notification(long sequence, A2aContracts.TaskState state) {
        return new A2aContracts.Notification("developer", "agent-task-1", "context-1", sequence, state,
                Instant.parse("2026-09-06T12:00:00Z"), List.of(), Map.of());
    }

    @WorkflowInterface
    public interface Harness {
        @WorkflowMethod String run(String taskId);
        @SignalMethod void update(A2aContracts.Notification notification);
    }

    public static final class HarnessImpl implements Harness {
        private final A2aTaskAwaiter awaiter = new A2aTaskAwaiter();
        @Override public String run(String taskId) {
            A2aTaskAwaiter.WaitResult result = awaiter.awaitNext(taskId, 0, Duration.ofMinutes(5));
            return result.reconciliationDue() ? "RECONCILE" : result.notification().state().name()
                    + ':' + result.notification().sequence();
        }
        @Override public void update(A2aContracts.Notification notification) { awaiter.accept(notification); }
    }

    @WorkflowInterface
    public interface ReconcileHarness {
        @WorkflowMethod String run();
    }

    public static final class ReconcileHarnessImpl implements ReconcileHarness {
        @Override public String run() {
            A2aActivities.GetTask getTask = io.temporal.workflow.Workflow.newActivityStub(
                    A2aActivities.GetTask.class,
                    TemporalActivityPolicies.forKind(TemporalActivityPolicies.Kind.A2A_GET));
            A2aContracts.Notification terminal = new A2aTaskAwaiter().awaitUntilTerminal(
                    "developer", submittedSnapshot(), Duration.ofSeconds(5), getTask);
            return terminal.state().name() + ':' + terminal.sequence();
        }
    }

    private static A2aContracts.TaskSnapshot submittedSnapshot() {
        return new A2aContracts.TaskSnapshot("agent-task-1", "context-1", A2aContracts.TaskState.SUBMITTED,
                Instant.parse("2026-09-06T12:00:00Z"), List.of(), Map.of("sequence", 0));
    }

    private static A2aContracts.TaskSnapshot completedSnapshot() {
        List<Map<String, Object>> transitions = List.of(
                Map.of("sequence", 1, "state", "TASK_STATE_WORKING",
                        "occurredAt", "2026-09-06T12:00:01Z"),
                Map.of("sequence", 2, "state", "TASK_STATE_COMPLETED",
                        "occurredAt", "2026-09-06T12:00:02Z"));
        return new A2aContracts.TaskSnapshot("agent-task-1", "context-1", A2aContracts.TaskState.COMPLETED,
                Instant.parse("2026-09-06T12:00:02Z"), List.of(),
                Map.of("sequence", 2, "transitions", transitions));
    }
}
