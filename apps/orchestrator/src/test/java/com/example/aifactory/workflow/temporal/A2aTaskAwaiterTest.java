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

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
    void getTaskThenCallbackWithDifferentTransportFieldsIsAnEquivalentDuplicate() {
        A2aTaskAwaiter awaiter = new A2aTaskAwaiter();
        A2aContracts.Notification snapshot = notification("architecture-agent", "context-1", 2,
                A2aContracts.TaskState.COMPLETED, "2026-09-08T09:40:49.748548Z", artifacts("result"),
                Map.of("source", "getTask"));
        A2aContracts.Notification callback = notification("architecture-agent", "context-1", 2,
                A2aContracts.TaskState.COMPLETED, "2026-09-08T09:40:49.751606Z", artifacts("result"), Map.of());

        awaiter.accept(snapshot);

        assertThatCode(() -> awaiter.accept(callback)).doesNotThrowAnyException();
        assertThat(current(awaiter)).isSameAs(snapshot);
    }

    @Test
    void callbackThenEquivalentGetTaskSnapshotKeepsTheCallback() {
        A2aTaskAwaiter awaiter = new A2aTaskAwaiter();
        A2aContracts.Notification callback = notification("architecture-agent", "context-1", 2,
                A2aContracts.TaskState.COMPLETED, "2026-09-08T09:40:49.751606Z", artifacts("result"), Map.of());
        A2aContracts.Notification snapshot = notification("architecture-agent", "context-1", 2,
                A2aContracts.TaskState.COMPLETED, "2026-09-08T09:40:49.748548Z", artifacts("result"),
                Map.of("source", "getTask"));

        awaiter.accept(callback);
        awaiter.accept(snapshot);

        assertThat(current(awaiter)).isSameAs(callback);
    }

    @Test
    void identicalDuplicateIsAcceptedAndOlderNotificationIsIgnored() {
        A2aTaskAwaiter awaiter = new A2aTaskAwaiter();
        A2aContracts.Notification current = notification(2, A2aContracts.TaskState.WORKING);

        awaiter.accept(current);
        assertThatCode(() -> awaiter.accept(current)).doesNotThrowAnyException();
        awaiter.accept(notification(1, A2aContracts.TaskState.SUBMITTED));

        assertThat(current(awaiter)).isSameAs(current);
    }

    @Test
    void timestampAloneDoesNotDefineTheTransitionIdentity() {
        A2aTaskAwaiter awaiter = new A2aTaskAwaiter();
        A2aContracts.Notification first = notification("developer", "context-1", 2,
                A2aContracts.TaskState.WORKING, "2026-09-08T09:40:49Z", List.of(), Map.of());
        A2aContracts.Notification second = notification("developer", "context-1", 2,
                A2aContracts.TaskState.WORKING, "2026-09-08T09:40:50Z", List.of(), Map.of());

        assertThat(A2aTaskAwaiter.sameTransition(first, second)).isTrue();
        awaiter.accept(first);
        assertThatCode(() -> awaiter.accept(second)).doesNotThrowAnyException();
    }

    @Test
    void metadataAloneDoesNotDefineTheTransitionIdentity() {
        A2aTaskAwaiter awaiter = new A2aTaskAwaiter();
        A2aContracts.Notification first = notification("developer", "context-1", 2,
                A2aContracts.TaskState.WORKING, "2026-09-08T09:40:49Z", List.of(),
                Map.of("source", "getTask"));
        A2aContracts.Notification second = notification("developer", "context-1", 2,
                A2aContracts.TaskState.WORKING, "2026-09-08T09:40:49Z", List.of(), Map.of());

        assertThat(A2aTaskAwaiter.sameTransition(first, second)).isTrue();
        awaiter.accept(first);
        assertThatCode(() -> awaiter.accept(second)).doesNotThrowAnyException();
    }

    @Test
    void sameSequenceRejectsDifferentContextRoleOrArtifacts() {
        A2aContracts.Notification baseline = notification("developer", "context-1", 2,
                A2aContracts.TaskState.COMPLETED, "2026-09-08T09:40:49Z", artifacts("result"), Map.of());

        assertDiverges(baseline, notification("developer", "context-2", 2,
                A2aContracts.TaskState.COMPLETED, "2026-09-08T09:40:49Z", artifacts("result"), Map.of()));
        assertDiverges(baseline, notification("architecture-agent", "context-1", 2,
                A2aContracts.TaskState.COMPLETED, "2026-09-08T09:40:49Z", artifacts("result"), Map.of()));
        assertDiverges(baseline, notification("developer", "context-1", 2,
                A2aContracts.TaskState.COMPLETED, "2026-09-08T09:40:49Z", List.of(), Map.of()));
        assertDiverges(baseline, notification("developer", "context-1", 2,
                A2aContracts.TaskState.COMPLETED, "2026-09-08T09:40:49Z", artifacts("changed"), Map.of()));
    }

    @Test
    void artifactMetadataRemainsPartOfTheTransitionIdentity() {
        A2aContracts.Notification baseline = notification("developer", "context-1", 2,
                A2aContracts.TaskState.COMPLETED, "2026-09-08T09:40:49Z",
                artifacts("result", Map.of("digest", "sha256:one")), Map.of());
        A2aContracts.Notification changed = notification("developer", "context-1", 2,
                A2aContracts.TaskState.COMPLETED, "2026-09-08T09:40:49Z",
                artifacts("result", Map.of("digest", "sha256:two")), Map.of());

        assertDiverges(baseline, changed);
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
        return notification("developer", "context-1", sequence, state, "2026-09-06T12:00:00Z",
                List.of(), Map.of());
    }

    private static A2aContracts.Notification notification(String agentRole, String contextId, long sequence,
                                                           A2aContracts.TaskState state, String occurredAt,
                                                           List<A2aContracts.Artifact> artifacts,
                                                           Map<String, Object> metadata) {
        return new A2aContracts.Notification(agentRole, "agent-task-1", contextId, sequence, state,
                Instant.parse(occurredAt), artifacts, metadata);
    }

    private static List<A2aContracts.Artifact> artifacts(String text) {
        return artifacts(text, Map.of());
    }

    private static List<A2aContracts.Artifact> artifacts(String text, Map<String, Object> metadata) {
        A2aContracts.Part part = new A2aContracts.Part("text/plain", text, Map.of(), null);
        return List.of(new A2aContracts.Artifact("artifact-1", "result", List.of(part), metadata));
    }

    private static void assertDiverges(A2aContracts.Notification current,
                                       A2aContracts.Notification incoming) {
        A2aTaskAwaiter awaiter = new A2aTaskAwaiter();
        awaiter.accept(current);
        assertThatThrownBy(() -> awaiter.accept(incoming))
                .isInstanceOf(SecurityException.class)
                .hasMessage("Divergent A2A workflow notification replay");
    }

    @SuppressWarnings("unchecked")
    private static A2aContracts.Notification current(A2aTaskAwaiter awaiter) {
        try {
            Field field = A2aTaskAwaiter.class.getDeclaredField("latest");
            field.setAccessible(true);
            return ((Map<String, A2aContracts.Notification>) field.get(awaiter)).get("agent-task-1");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
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
