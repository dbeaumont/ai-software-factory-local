package com.example.aifactory.service;

import com.example.aifactory.model.LlmMode;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

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
    void requiresAQualityGateInsteadOfTreatingSkippedAnalysisAsSuccess() {
        assertThrows(IllegalStateException.class,
                () -> TaskService.requireQualityGate("Skipped because AI_FACTORY_SONAR_TOKEN is not configured."));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsApprovalBeforeTheHumanApprovalGate() {
        TestableTaskService service = new TestableTaskService();
        TaskState state = new TaskState("task-1", "AF-0001", new TaskRequest(
                "http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.LOCAL));
        Map<String, TaskState> tasks = (Map<String, TaskState>) ReflectionTestUtils.getField(service, "tasks");
        assertNotNull(tasks);
        tasks.put(state.id, state);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.approve(state.id));

        assertEquals("Task is not waiting for approval", error.getMessage());
        assertEquals(TaskStatus.QUEUED, state.status);
        assertEquals(0, state.steps.size());
    }

    private static final class TestableTaskService extends TaskService {
        private TestableTaskService() {
            super(null, null, null, null, null, new AgentResponseValidator(new ObjectMapper()), null, null,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        }
    }
}
