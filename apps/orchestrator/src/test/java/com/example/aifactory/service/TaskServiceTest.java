package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static final class TestableTaskService extends TaskService {
        private TestableTaskService() {
            super(null, null, null, null, null, null, null, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        }
    }
}
