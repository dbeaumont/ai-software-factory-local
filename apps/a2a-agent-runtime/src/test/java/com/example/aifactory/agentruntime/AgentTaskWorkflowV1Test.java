package com.example.aifactory.agentruntime;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.VersioningBehavior;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.WorkflowVersioningBehavior;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AgentTaskWorkflowV1Test {

    @Test
    void startsExactlyOneDeterministicPinnedWorkflowOnTheRoleQueue() throws Exception {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            AgentTemporalProperties properties = new AgentTemporalProperties(
                    true, "unused", "default", "a2a-agents", "build-1");
            String queue = properties.taskQueue("developer");
            Worker worker = environment.newWorker(queue);
            worker.registerWorkflowImplementationTypes(AgentTaskWorkflowV1Impl.class);
            environment.start();
            TemporalAgentTaskWorkflowGateway gateway = new TemporalAgentTaskWorkflowGateway(
                    environment.getWorkflowClient(), properties, "developer");
            A2aSendMessageService.Submission submission = new A2aSendMessageService.Submission(
                    "task-1", "context-1", "message-1", "developer", "developer.code-task-v1",
                    "orchestrator", "tenant-a", "delegation-1", Instant.now());

            AgentTaskWorkflowStarter.Execution first = gateway.start(submission, "{\"schema_version\":\"1\"}");
            AgentTaskWorkflowStarter.Execution replay = gateway.start(submission, "{\"schema_version\":\"1\"}");
            assertThat(first.workflowId()).isEqualTo("a2a-agent-task-v1/developer/task-1");
            assertThat(replay.workflowId()).isEqualTo(first.workflowId());

            AgentTaskWorkflowV1 workflow = environment.getWorkflowClient()
                    .newWorkflowStub(AgentTaskWorkflowV1.class, first.workflowId());
            assertThat(workflow.state()).isEqualTo("SUBMITTED");
            workflow.complete(new AgentTaskWorkflowV1.Outcome("COMPLETED", "a".repeat(64), "validated"));
            assertThat(WorkflowStub.fromTyped(workflow).getResult(AgentTaskWorkflowV1.Outcome.class).state())
                    .isEqualTo("COMPLETED");

            WorkflowVersioningBehavior behavior = AgentTaskWorkflowV1Impl.class
                    .getMethod("run", AgentTaskWorkflowV1.Input.class)
                    .getAnnotation(WorkflowVersioningBehavior.class);
            assertThat(behavior.value()).isEqualTo(VersioningBehavior.PINNED);
            assertThat(queue).isEqualTo("a2a-agent-developer-v1");
        }
    }

    @Test
    void cancellationSignalTerminatesTheWaitingWorkflowIdempotently() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            String queue = "a2a-agent-developer-v1";
            environment.newWorker(queue).registerWorkflowImplementationTypes(AgentTaskWorkflowV1Impl.class);
            environment.start();
            AgentTaskWorkflowV1 workflow = environment.getWorkflowClient().newWorkflowStub(
                    AgentTaskWorkflowV1.class, io.temporal.client.WorkflowOptions.newBuilder()
                            .setWorkflowId("a2a-agent-task-v1/developer/task-2").setTaskQueue(queue).build());
            WorkflowClient.start(workflow::run, new AgentTaskWorkflowV1.Input(
                    "task-2", "context-2", "developer", "developer.code-task-v1", "{}"));
            workflow.cancel("requested");
            workflow.cancel("duplicate");
            AgentTaskWorkflowV1.Outcome outcome = WorkflowStub.fromTyped(workflow)
                    .getResult(AgentTaskWorkflowV1.Outcome.class);
            assertThat(outcome.state()).isEqualTo("CANCELED");
            assertThat(outcome.detail()).isEqualTo("requested");
        }
    }
}
