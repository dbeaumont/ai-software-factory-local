package com.example.aifactory.workflow.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.failure.ApplicationFailure;
import io.temporal.failure.TimeoutFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalFailureModesTest {
    private static final String TASK_QUEUE = "failure-modes-test";

    @Test
    void retriesAfterTimeoutAndIgnoresTheLateFirstResponse() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch lateResponseCompleted = new CountDownLatch(1);
        TimeoutActivities activities = () -> {
            if (attempts.incrementAndGet() == 1) {
                sleep(Duration.ofMillis(350));
                lateResponseCompleted.countDown();
                return "late-first-response";
            }
            return "retry-response";
        };

        try (TestWorkflowEnvironment environment = environment(TimeoutWorkflowImpl.class, activities)) {
            TimeoutWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(TimeoutWorkflow.class,
                    options("timeout-and-late-response"));

            assertThat(workflow.run()).isEqualTo("retry-response");
            assertThat(lateResponseCompleted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(attempts).hasValue(2);
        }
    }

    @Test
    void retryOfADuplicateDeliveryReusesTheIdempotentEffect() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger externalEffects = new AtomicInteger();
        Map<String, String> resultsByKey = new ConcurrentHashMap<>();
        DuplicateActivities activities = key -> {
            String result = resultsByKey.computeIfAbsent(key, ignored -> {
                externalEffects.incrementAndGet();
                return "effect-result";
            });
            if (attempts.incrementAndGet() == 1) {
                throw ApplicationFailure.newFailure("ack lost after effect", "TRANSIENT_ACK_LOSS");
            }
            return result;
        };

        try (TestWorkflowEnvironment environment = environment(DuplicateWorkflowImpl.class, activities)) {
            DuplicateWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(DuplicateWorkflow.class,
                    options("duplicate-delivery"));

            assertThat(workflow.run("task-1-attempt-1-operation-1")).isEqualTo("effect-result");
            assertThat(attempts).hasValue(2);
            assertThat(externalEffects).as("the idempotency key protects the external side effect").hasValue(1);
        }
    }

    @Test
    void reportsAnUnavailableActivityTaskQueueAfterScheduleToStartTimeout() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker workflowWorker = environment.newWorker(TASK_QUEUE);
            workflowWorker.registerWorkflowImplementationTypes(UnavailableQueueWorkflowImpl.class);
            environment.start();
            UnavailableQueueWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    UnavailableQueueWorkflow.class, options("unavailable-queue"));

            assertThat(workflow.run()).isEqualTo("TIMEOUT_TYPE_SCHEDULE_TO_START");
        }
    }

    private static TestWorkflowEnvironment environment(Class<?> workflowType, Object activities) {
        TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance();
        Worker worker = environment.newWorker(TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(workflowType);
        worker.registerActivitiesImplementations(activities);
        environment.start();
        return environment;
    }

    private static WorkflowOptions options(String workflowId) {
        return WorkflowOptions.newBuilder().setWorkflowId(workflowId).setTaskQueue(TASK_QUEUE).build();
    }

    private static ActivityOptions retriedOptions() {
        return ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMillis(100))
                .setScheduleToCloseTimeout(Duration.ofSeconds(2))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2)
                        .setInitialInterval(Duration.ofMillis(10)).build())
                .build();
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @WorkflowInterface
    public interface TimeoutWorkflow {
        @WorkflowMethod
        String run();
    }

    public static class TimeoutWorkflowImpl implements TimeoutWorkflow {
        private final TimeoutActivities activities = Workflow.newActivityStub(
                TimeoutActivities.class, retriedOptions());

        @Override
        public String run() {
            return activities.call();
        }
    }

    @ActivityInterface
    public interface TimeoutActivities {
        String call();
    }

    @WorkflowInterface
    public interface DuplicateWorkflow {
        @WorkflowMethod
        String run(String idempotencyKey);
    }

    public static class DuplicateWorkflowImpl implements DuplicateWorkflow {
        private final DuplicateActivities activities = Workflow.newActivityStub(
                DuplicateActivities.class, retriedOptions());

        @Override
        public String run(String idempotencyKey) {
            return activities.apply(idempotencyKey);
        }
    }

    @ActivityInterface
    public interface DuplicateActivities {
        String apply(String idempotencyKey);
    }

    @WorkflowInterface
    public interface UnavailableQueueWorkflow {
        @WorkflowMethod
        String run();
    }

    public static class UnavailableQueueWorkflowImpl implements UnavailableQueueWorkflow {
        private final UnavailableQueueActivities activities = Workflow.newActivityStub(
                UnavailableQueueActivities.class,
                ActivityOptions.newBuilder().setTaskQueue("intentionally-unavailable-queue")
                        .setScheduleToStartTimeout(Duration.ofSeconds(1))
                        .setStartToCloseTimeout(Duration.ofSeconds(1))
                        .setScheduleToCloseTimeout(Duration.ofSeconds(2))
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                        .build());

        @Override
        public String run() {
            try {
                activities.call();
                return "UNEXPECTED_SUCCESS";
            } catch (ActivityFailure failure) {
                if (failure.getCause() instanceof TimeoutFailure timeout) {
                    return timeout.getTimeoutType().name();
                }
                return failure.getCause().getClass().getSimpleName();
            }
        }
    }

    @ActivityInterface
    public interface UnavailableQueueActivities {
        String call();
    }
}
