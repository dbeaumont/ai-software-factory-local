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
}
