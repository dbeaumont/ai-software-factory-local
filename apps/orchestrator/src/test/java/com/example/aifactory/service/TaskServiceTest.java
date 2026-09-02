package com.example.aifactory.service;

import com.example.aifactory.model.LlmMode;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskServiceTest {
    @Test
    void stripFenceRemovesMarkdownAndLeadingExplanation() {
        String response = "Here is the patch:\n```diff\ndiff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new\n```";

        assertEquals("diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new",
                TaskService.stripFence(response));
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
        assertEquals(1_200, TaskService.maxTokensFor("planner"));
        assertEquals(1_200, TaskService.maxTokensFor("developer"));
        assertEquals(1_600, TaskService.maxTokensFor("patch-repair"));
        assertEquals(1_200, TaskService.maxTokensFor("reviewer"));
        assertEquals(1_200, TaskService.maxTokensFor("unknown"));
    }

    @Test
    void retriesAnInvalidPlannerContractOnlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger retries = new AtomicInteger();

        String response = TaskService.withSingleContractRetry(
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

        String response = TaskService.withSingleContractRetry(
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

        String response = TaskService.withSingleContractRetry(
                () -> {
                    throw new LlmCompletionException("length", true, "truncated");
                }, () -> "valid", value -> true,
                reason -> {
                    assertEquals("length", reason);
                    retries.incrementAndGet();
                });

        assertEquals("valid", response);
        assertEquals(1, retries.get());
        assertEquals(2_400, TaskService.retryMaxTokensFor("planner"));
    }

    @Test
    void doesNotRetryARefusal() {
        assertThrows(LlmCompletionException.class, () -> TaskService.withSingleContractRetry(
                () -> {
                    throw new LlmCompletionException("refusal", false, "refused");
                }, () -> "unexpected", value -> true,
                reason -> {
                    throw new AssertionError("A refusal must not be retried");
                }));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsApprovalBeforeTheHumanApprovalGate() {
        TestableTaskService service = new TestableTaskService();
        TaskState state = new TaskState("task-1", "AF-0001", new TaskRequest(
                "http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD));
        Map<String, TaskState> tasks = (Map<String, TaskState>) ReflectionTestUtils.getField(service, "tasks");
        assertNotNull(tasks);
        tasks.put(state.id, state);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.approve(state.id));

        assertEquals("Task is not waiting for approval", error.getMessage());
        assertEquals(TaskStatus.QUEUED, state.status);
        assertEquals(0, state.steps.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsApprovalWithoutAPolicyApprovedPendingEffect() {
        TestableTaskService service = new TestableTaskService();
        TaskState state = new TaskState("task-175", "AF-0175", new TaskRequest(
                "http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD));
        state.status = TaskStatus.WAITING_APPROVAL;
        Map<String, TaskState> tasks = (Map<String, TaskState>) ReflectionTestUtils.getField(service, "tasks");
        assertNotNull(tasks);
        tasks.put(state.id, state);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.approve(state.id));

        assertEquals("No policy-approved effect is awaiting confirmation", error.getMessage());
        assertEquals(TaskStatus.WAITING_APPROVAL, state.status);
    }

    private static final class TestableTaskService extends TaskService {
        private TestableTaskService() {
            super(null, null, null, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        }
    }
}
