package com.example.aifactory.workflow.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalWorkerRestartResilienceTest {
    private static final String TASK_QUEUE = "llm-restart-test";

    @Test
    void resumesAnLlmDelegationAfterWorkerPollingRestarts() throws Exception {
        CountDownLatch firstAttemptStarted = new CountDownLatch(1);
        CountDownLatch allowFirstAttemptToFail = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        LlmActivities activities = task -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                firstAttemptStarted.countDown();
                await(allowFirstAttemptToFail);
                throw ApplicationFailure.newFailure("worker stopped", "WORKER_RESTART");
            }
            return "completed:" + task;
        };

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(LlmDelegationWorkflowImpl.class);
            worker.registerActivitiesImplementations(activities);
            environment.start();
            LlmDelegationWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    LlmDelegationWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId("llm-restart-1").setTaskQueue(TASK_QUEUE).build());

            WorkflowClient.start(workflow::run, "delegation-1");
            assertThat(firstAttemptStarted.await(2, TimeUnit.SECONDS)).isTrue();
            worker.suspendPolling();
            allowFirstAttemptToFail.countDown();

            worker.resumePolling();
            assertThat(WorkflowStub.fromTyped(workflow).getResult(String.class))
                    .isEqualTo("completed:delegation-1");
            assertThat(attempts).hasValue(2);
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
    public interface LlmDelegationWorkflow {
        @WorkflowMethod
        String run(String task);
    }

    public static class LlmDelegationWorkflowImpl implements LlmDelegationWorkflow {
        private final LlmActivities activities = Workflow.newActivityStub(LlmActivities.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(5))
                        .setScheduleToCloseTimeout(Duration.ofSeconds(20))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3)
                                .setInitialInterval(Duration.ofMillis(10)).build())
                        .build());

        @Override
        public String run(String task) {
            return activities.invoke(task);
        }
    }

    @ActivityInterface
    public interface LlmActivities {
        String invoke(String task);
    }
}
