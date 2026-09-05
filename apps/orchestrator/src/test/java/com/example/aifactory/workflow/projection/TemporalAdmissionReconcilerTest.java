package com.example.aifactory.workflow.projection;

import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.workflow.TaskMemory;
import com.example.aifactory.workflow.WorkflowCoordinator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalAdmissionReconcilerTest {
    @Test
    void startsPendingAdmissionsAndMarksThemCompleted() {
        TaskState task = task();
        RecordingMemory memory = new RecordingMemory(task);
        WorkflowCoordinator coordinator = new WorkflowCoordinator() {
            @Override public void start(TaskState state) {
                state.bindExecution("PIPELINE", "run-recovered", "build-1", 1, 0, 1);
            }
            @Override public void resumeAfterApproval(TaskState state) {}
        };

        new TemporalAdmissionReconciler(memory, coordinator).reconcile();

        assertThat(memory.started).isEqualTo(1);
        assertThat(task.workflowRunId).isEqualTo("run-recovered");
    }

    @Test
    void defersFailureAndContinuesWithTheRestOfTheBatch() {
        TaskState first = task();
        TaskState second = new TaskState("task-2", "AF-0002", first.request);
        RecordingMemory memory = new RecordingMemory(first, second);
        AtomicInteger calls = new AtomicInteger();
        WorkflowCoordinator coordinator = new WorkflowCoordinator() {
            @Override public void start(TaskState state) {
                if (calls.getAndIncrement() == 0) throw new IllegalStateException("Temporal unavailable");
            }
            @Override public void resumeAfterApproval(TaskState state) {}
        };

        new TemporalAdmissionReconciler(memory, coordinator).reconcile();

        assertThat(memory.failed).isEqualTo(1);
        assertThat(memory.started).isEqualTo(1);
        assertThat(calls).hasValue(2);
    }

    private static TaskState task() {
        return new TaskState("task-1", "AF-0001",
                new TaskRequest("https://example.test/customer-api.git", "main", "change", null));
    }

    private static final class RecordingMemory implements TaskMemory {
        private final List<TaskState> pending;
        private int started;
        private int failed;

        private RecordingMemory(TaskState... pending) {
            this.pending = List.of(pending);
        }

        @Override public List<TaskState> pendingAdmissions(int limit) { return pending; }
        @Override public void workflowStarted(TaskState state) { started++; }
        @Override public void admissionFailed(TaskState state, RuntimeException failure) { failed++; }
        @Override public void save(TaskState state) {}
        @Override public Optional<TaskState> find(String taskId) { return Optional.empty(); }
        @Override public List<TaskState> list() { return List.of(); }
    }
}
