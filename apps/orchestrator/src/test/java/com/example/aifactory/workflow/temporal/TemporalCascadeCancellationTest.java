package com.example.aifactory.workflow.temporal;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityCancellationType;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.client.ActivityCompletionException;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.ChildWorkflowCancellationType;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemporalCascadeCancellationTest {
    private static final String TASK_QUEUE = "cascade-cancellation-test";

    @Test
    void cancelsChildWorkflowAndSandboxActivityWithTheRoot() throws Exception {
        CountDownLatch sandboxStarted = new CountDownLatch(1);
        CountDownLatch sandboxCancellationObserved = new CountDownLatch(1);
        SandboxActivities sandbox = executionId -> {
            sandboxStarted.countDown();
            try {
                while (true) {
                    Activity.getExecutionContext().heartbeat(executionId);
                    Thread.sleep(20);
                }
            } catch (ActivityCompletionException cancelled) {
                sandboxCancellationObserved.countDown();
                throw cancelled;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                sandboxCancellationObserved.countDown();
                throw new CanceledFailure("sandbox interrupted");
            }
        };

        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker(TASK_QUEUE);
            worker.registerWorkflowImplementationTypes(CancellationRootWorkflowImpl.class,
                    CancellationChildWorkflowImpl.class);
            worker.registerActivitiesImplementations(sandbox);
            environment.start();
            CancellationRootWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    CancellationRootWorkflow.class,
                    WorkflowOptions.newBuilder().setWorkflowId("cascade-root-1")
                            .setTaskQueue(TASK_QUEUE).build());

            WorkflowClient.start(workflow::run, "sandbox-execution-99");
            assertThat(sandboxStarted.await(2, TimeUnit.SECONDS)).isTrue();

            WorkflowStub.fromTyped(workflow).cancel("test cancellation");

            assertThatThrownBy(() -> WorkflowStub.fromTyped(workflow).getResult(String.class))
                    .isInstanceOf(WorkflowFailedException.class)
                    .hasCauseInstanceOf(CanceledFailure.class);
            assertThat(sandboxCancellationObserved.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @WorkflowInterface
    public interface CancellationRootWorkflow {
        @WorkflowMethod
        String run(String executionId);
    }

    public static class CancellationRootWorkflowImpl implements CancellationRootWorkflow {
        @Override
        public String run(String executionId) {
            CancellationChildWorkflow child = Workflow.newChildWorkflowStub(CancellationChildWorkflow.class,
                    ChildWorkflowOptions.newBuilder().setWorkflowId("cascade-child-1")
                            .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_REQUEST_CANCEL)
                            .setCancellationType(ChildWorkflowCancellationType.WAIT_CANCELLATION_COMPLETED)
                            .build());
            return child.run(executionId);
        }
    }

    @WorkflowInterface
    public interface CancellationChildWorkflow {
        @WorkflowMethod
        String run(String executionId);
    }

    public static class CancellationChildWorkflowImpl implements CancellationChildWorkflow {
        private final SandboxActivities sandbox = Workflow.newActivityStub(SandboxActivities.class,
                ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofMinutes(5))
                        .setScheduleToCloseTimeout(Duration.ofMinutes(10))
                        .setHeartbeatTimeout(Duration.ofMillis(100))
                        .setCancellationType(ActivityCancellationType.WAIT_CANCELLATION_COMPLETED)
                        .build());

        @Override
        public String run(String executionId) {
            return sandbox.run(executionId);
        }
    }

    @ActivityInterface
    public interface SandboxActivities {
        String run(String executionId);
    }
}
