package com.example.aifactory.service;

import com.example.aifactory.model.LlmMode;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskStatus;
import com.example.aifactory.model.PendingEffect;
import com.example.aifactory.model.ManifestApprovalRequest;
import com.example.aifactory.workflow.WorkflowCoordinator;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceTest {
    @Test
    void stripFenceRemovesMarkdownAndLeadingExplanation() {
        String response = "Here is the patch:\n```diff\ndiff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new\n```";

        assertEquals("diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new",
                DeterministicWorkflowCoordinator.stripFence(response));
    }

    @Test
    void normalizesIncorrectHunkLineCounts() {
        String patch = "@@ -17,1 +18,1 @@ class CustomerControllerTest {\n" +
                "     void listsCustomers() throws Exception {\n" +
                "         mvc.perform(get(\"/customers\")).andExpect(status().isOk());\n" +
                "     }\n" +
                "+\n" +
                "+    @Test\n" +
                "+    void returnsNotFound() throws Exception {\n" +
                "+        mvc.perform(get(\"/customers/999\")).andExpect(status().isNotFound());\n" +
                "+    }\n" +
                " }";

        assertEquals("@@ -17,4 +18,9 @@ class CustomerControllerTest {", UnifiedDiffNormalizer.normalize(patch).lines().findFirst().orElseThrow());
        assertEquals('\n', UnifiedDiffNormalizer.normalize(patch).charAt(UnifiedDiffNormalizer.normalize(patch).length() - 1));
    }

    @Test
    void generatesSequentialTicketNumbers() {
        TestableTaskService service = new TestableTaskService();
        String first = service.nextTicketNumber();
        String second = service.nextTicketNumber();

        assertTrue(first.matches("AF-\\d{4}"));
        assertEquals("AF-0001", first);
        assertEquals("AF-0002", second);
    }

    @Test
    void boundsLlmOutputByAgentRole() {
        assertEquals(1_200, DeterministicWorkflowCoordinator.maxTokensFor("planner"));
        assertEquals(1_200, DeterministicWorkflowCoordinator.maxTokensFor("developer"));
        assertEquals(1_600, DeterministicWorkflowCoordinator.maxTokensFor("patch-repair"));
        assertEquals(1_200, DeterministicWorkflowCoordinator.maxTokensFor("reviewer"));
        assertEquals(1_200, DeterministicWorkflowCoordinator.maxTokensFor("unknown"));
    }

    @Test
    void retriesAnInvalidPlannerContractOnlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();

        String response = DeterministicWorkflowCoordinator.withSingleContractRetry(
                () -> calls.incrementAndGet() == 1 ? "{}" : "{\"status\":\"IMPLEMENTABLE\"}",
                () -> calls.incrementAndGet() == 1 ? "{}" : "{\"status\":\"IMPLEMENTABLE\"}",
                value -> value.contains("IMPLEMENTABLE"), reason -> retries.incrementAndGet());

        assertEquals("{\"status\":\"IMPLEMENTABLE\"}", response);
        assertEquals(2, calls.get());
        assertEquals(1, retries.get());
    }

    @Test
    void doesNotRetryAValidPlannerDecision() {
        AtomicInteger calls = new AtomicInteger();

        String response = DeterministicWorkflowCoordinator.withSingleContractRetry(
                () -> {
                    calls.incrementAndGet();
                    return "{\"status\":\"NEEDS_CLARIFICATION\"}";
                }, () -> {
                    calls.incrementAndGet();
                    return "unexpected";
                }, value -> value.contains("NEEDS_CLARIFICATION"),
                reason -> {
                    throw new AssertionError("A valid contract must not be retried");
                });

        assertEquals("{\"status\":\"NEEDS_CLARIFICATION\"}", response);
        assertEquals(1, calls.get());
    }

    @Test
    void retriesATruncatedPlannerCompletionWithTheLargerBudget() {
        AtomicInteger retries = new AtomicInteger();

        String response = DeterministicWorkflowCoordinator.withSingleContractRetry(
                () -> {
                    throw new LlmCompletionException("length", true, "truncated");
                }, () -> "valid", value -> true,
                reason -> {
                    assertEquals("length", reason);
                    retries.incrementAndGet();
                });

        assertEquals("valid", response);
        assertEquals(1, retries.get());
        assertEquals(2_400, DeterministicWorkflowCoordinator.retryMaxTokensFor("planner"));
    }

    @Test
    void doesNotRetryARefusal() {
        assertThrows(LlmCompletionException.class, () -> DeterministicWorkflowCoordinator.withSingleContractRetry(
                () -> {
                    throw new LlmCompletionException("refusal", false, "refused");
                }, () -> "unexpected", value -> true,
                reason -> {
                    throw new AssertionError("A refusal must not be retried");
                }));
    }

    @Test
    void rejectsApprovalBeforeTheHumanApprovalGate() {
        TestableTaskService service = new TestableTaskService();
        TaskState state = new TaskState("task-1", "AF-0001", new TaskRequest(
                "http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD));
        service.memory.save(state);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.approve(state.id));

        assertEquals("Task is not waiting for approval", error.getMessage());
        assertEquals(TaskStatus.QUEUED, state.status);
        assertEquals(0, state.steps.size());
    }

    @Test
    void rejectsApprovalWithoutAPolicyApprovedPendingEffect() {
        TestableTaskService service = new TestableTaskService();
        TaskState state = new TaskState("task-175", "AF-0175", new TaskRequest(
                "http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD));
        state.status = TaskStatus.WAITING_APPROVAL;
        service.memory.save(state);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.approve(state.id));

        assertEquals("No policy-approved effect is awaiting confirmation", error.getMessage());
        assertEquals(TaskStatus.WAITING_APPROVAL, state.status);
    }

    @Test
    void rejectsApprovalWhileAHumanDecisionIsPending() {
        TestableTaskService service = new TestableTaskService();
        TaskState state = new TaskState("task-176", "AF-0176", new TaskRequest(
                "http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD));
        state.status = TaskStatus.WAITING_APPROVAL;
        state.recordHumanAction("decision-1", "contradiction-1", "ARCHITECTURE", "Choose contract",
                "d".repeat(64), "PENDING");
        service.memory.save(state);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.approve(state.id));

        assertEquals("Human decisions must be answered before approval", error.getMessage());
        assertEquals(TaskStatus.WAITING_APPROVAL, state.status);
    }

    @Test
    void approvesOnlyTheManifestCurrentlyDisplayedToTheOperator() {
        TestableTaskService service = new TestableTaskService();
        TaskState state = new TaskState("task-177", "AF-0177", new TaskRequest(
                "http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD));
        state.status = TaskStatus.WAITING_APPROVAL;
        state.pendingEffect = new PendingEffect("scm.create_draft_pull_request", java.util.Map.of(), "Create PR",
                "ALLOW", true);
        state.bindApprovalManifest("a".repeat(64), "evidence://task-177/manifest", "b".repeat(64));
        service.memory.save(state);

        IllegalStateException stale = assertThrows(IllegalStateException.class, () -> service.approveManifest(
                state.id, new ManifestApprovalRequest("a".repeat(64), "c".repeat(64))));
        assertEquals("Approval manifest changed; reload the task before approving", stale.getMessage());
        assertEquals(TaskStatus.WAITING_APPROVAL, state.status);

        service.approveManifest(state.id, new ManifestApprovalRequest("a".repeat(64), "b".repeat(64)));
        assertEquals(TaskStatus.APPROVED, state.status);
    }

    private static final class TestableTaskService extends TaskService {
        private final InMemoryTaskMemory memory;

        private TestableTaskService() {
            this(new InMemoryTaskMemory());
        }

        private TestableTaskService(InMemoryTaskMemory memory) {
            super(null, null, new WorkflowCoordinator() {
                @Override public void start(TaskState task) {}
                @Override public void resumeAfterApproval(TaskState task) {}
            }, memory, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
            this.memory = memory;
        }
    }
}
