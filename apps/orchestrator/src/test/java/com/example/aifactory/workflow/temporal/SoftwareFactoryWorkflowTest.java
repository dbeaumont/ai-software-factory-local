package com.example.aifactory.workflow.temporal;

import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SoftwareFactoryWorkflowTest {
    @Test
    void executesAsATemporalRootWorkflow() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("software-factory-test");
            worker.registerWorkflowImplementationTypes(
                    SoftwareFactoryWorkflowImpl.class, DelegationWorkflowImpl.class);
            environment.start();
            SoftwareFactoryWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId(TemporalIds.workflow("task-1", "attempt-1"))
                            .setTaskQueue("software-factory-test").build());

            SoftwareFactoryWorkflow.Result result = workflow.run(new SoftwareFactoryWorkflow.Request(
                    "task-1", "attempt-1", "a".repeat(40), "change"));

            assertThat(result.status()).isEqualTo("READY_FOR_DELEGATION");
            assertThat(result.chronology()).containsExactly("WORKFLOW_STARTED");
            assertThat(result.delegations()).isEmpty();
            assertThat(result.approvedManifestId()).isNull();
            assertThat(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId())
                    .isEqualTo(TemporalIds.workflow("task-1", "attempt-1"));
        }
    }

    @Test
    void executesAGenericDelegationAsAChildWorkflow() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("software-factory-test");
            worker.registerWorkflowImplementationTypes(
                    SoftwareFactoryWorkflowImpl.class, DelegationWorkflowImpl.class);
            environment.start();
            SoftwareFactoryWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryWorkflow.class, WorkflowOptions.newBuilder()
                            .setWorkflowId(TemporalIds.workflow("task-2", "attempt-1"))
                            .setTaskQueue("software-factory-test").build());
            DelegationWorkflow.Request child = new DelegationWorkflow.Request(
                    "task-2", "attempt-1", "code-1", "supervisor", "code-agent",
                    "a".repeat(40), "produce a patch proposal");

            SoftwareFactoryWorkflow.Result result = workflow.run(new SoftwareFactoryWorkflow.Request(
                    "task-2", "attempt-1", "a".repeat(40), "change", java.util.List.of(child)));

            assertThat(result.status()).isEqualTo("DELEGATIONS_COMPLETED");
            assertThat(result.delegations()).containsExactly(
                    new DelegationWorkflow.Result("code-1", "code-agent", "READY_FOR_ACTIVITIES"));
            assertThat(result.chronology()).containsExactly("WORKFLOW_STARTED", "DELEGATION_COMPLETED:code-1");
        }
    }

    @Test
    void resumesOnlyForAnApprovalBoundToTheSubmittedManifest() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("software-factory-test");
            worker.registerWorkflowImplementationTypes(
                    SoftwareFactoryWorkflowImpl.class, DelegationWorkflowImpl.class);
            environment.start();
            SoftwareFactoryWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryWorkflow.class, WorkflowOptions.newBuilder()
                            .setWorkflowId(TemporalIds.workflow("task-3", "attempt-1"))
                            .setTaskQueue("software-factory-test").build());
            String manifestId = "b".repeat(64);
            String digest = "c".repeat(64);
            SoftwareFactoryWorkflow.Request request = new SoftwareFactoryWorkflow.Request(
                    "task-3", "attempt-1", "a".repeat(40), "change", java.util.List.of(),
                    new SoftwareFactoryWorkflow.ApprovalRequest(
                            manifestId, "evidence://task-3/attempt-1/manifest/" + manifestId, digest));
            io.temporal.client.WorkflowClient.start(workflow::run, request);

            workflow.approve(new SoftwareFactoryWorkflow.ApprovalSignal(
                    "task-3", "attempt-1", "d".repeat(64), digest,
                    "APPROVE", "wrong", "2026-09-02T10:00:00Z"));
            workflow.approve(new SoftwareFactoryWorkflow.ApprovalSignal(
                    "task-3", "attempt-1", manifestId, digest,
                    "APPROVE", "reviewer@example.test", "2026-09-02T10:01:00Z"));
            SoftwareFactoryWorkflow.Result result = WorkflowStub.fromTyped(workflow)
                    .getResult(SoftwareFactoryWorkflow.Result.class);

            assertThat(result.status()).isEqualTo("APPROVED");
            assertThat(result.approvedManifestId()).isEqualTo(manifestId);
            assertThat(result.approvedBy()).isEqualTo("reviewer@example.test");
            assertThat(result.chronology()).containsExactly(
                    "WORKFLOW_STARTED", "WAITING_APPROVAL:" + manifestId, "APPROVED:" + manifestId);
        }
    }
}
