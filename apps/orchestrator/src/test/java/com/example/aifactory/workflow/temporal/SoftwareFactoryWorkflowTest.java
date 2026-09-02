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
            worker.registerWorkflowImplementationTypes(SoftwareFactoryWorkflowImpl.class);
            environment.start();
            SoftwareFactoryWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId("task-task-1-attempt-attempt-1")
                            .setTaskQueue("software-factory-test").build());

            SoftwareFactoryWorkflow.Result result = workflow.run(new SoftwareFactoryWorkflow.Request(
                    "task-1", "attempt-1", "a".repeat(40), "change"));

            assertThat(result.status()).isEqualTo("READY_FOR_DELEGATION");
            assertThat(result.chronology()).containsExactly("WORKFLOW_STARTED");
            assertThat(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId())
                    .isEqualTo("task-task-1-attempt-attempt-1");
        }
    }
}
