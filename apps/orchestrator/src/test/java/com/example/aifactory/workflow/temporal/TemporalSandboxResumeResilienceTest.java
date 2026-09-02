package com.example.aifactory.workflow.temporal;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalSandboxResumeResilienceTest {
    private static final String TASK_QUEUE = "sandbox-resume-test";

    @Test
    void resumesTheExistingSandboxJobFromHeartbeatExecutionId() throws Exception {
        CountDownLatch firstAttemptStarted = new CountDownLatch(1);
        CountDownLatch simulateWorkerLoss = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger submissions = new AtomicInteger();
        SandboxActivities activities = () -> {
            int attempt = attempts.incrementAndGet();
            String executionId = Activity.getExecutionContext().getHeartbeatDetails(String.class)
                    .orElseGet(() -> {
                        submissions.incrementAndGet();
                        return "sandbox-execution-42";
                    });
            Activity.getExecutionContext().heartbeat(executionId);
            if (attempt == 1) {
                firstAttemptStarted.countDown();
                await(simulateWorkerLoss);
                throw ApplicationFailure.newFailure("sandbox worker lost", "WORKER_RESTART");
            }
            return executionId;
        };

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(SandboxWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
            environment.start();
            SandboxWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(SandboxWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId("sandbox-resume-1")
                            .setTaskQueue(TASK_QUEUE).build());

            WorkflowClient.start(workflow::run);
            assertThat(firstAttemptStarted.await(2, TimeUnit.SECONDS)).isTrue();
            worker.suspendPolling();
            simulateWorkerLoss.countDown();
            worker.resumePolling();

            assertThat(WorkflowStub.fromTyped(workflow).getResult(String.class))
                    .isEqualTo("sandbox-execution-42");
            assertThat(attempts).hasValue(2);
            assertThat(submissions).as("the external sandbox job is submitted only once").hasValue(1);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("test synchronization timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @WorkflowInterface
    public interface SandboxWorkflow {
        @WorkflowMethod
        String run();
    }

    public static class SandboxWorkflowImpl implements SandboxWorkflow {
        private final SandboxActivities activities = Workflow.newActivityStub(
                SandboxActivities.class, TemporalActivityPolicies.forKind(TemporalActivityPolicies.Kind.SANDBOX));

        @Override
        public String run() {
            return activities.runOrResume();
        }
    }

    @ActivityInterface
    public interface SandboxActivities {
        String runOrResume();
    }
}
