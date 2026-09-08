package com.example.aifactory.service;

import com.example.aifactory.model.LlmMode;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskStatus;
import com.example.aifactory.model.PendingEffect;
import com.example.aifactory.model.ManifestApprovalRequest;
import com.example.aifactory.model.OperatorActionRequest;
import com.example.aifactory.workflow.WorkflowCoordinator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class TaskServiceTest {
    @Test
    void stripFenceRemovesMarkdownAndLeadingExplanation() {
        String response = "Here is the patch:\n```diff\ndiff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new\n```";

        assertEquals("diff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new",
                PipelineStepService.stripFence(response));
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
        assertEquals(TaskStatus.WAITING_APPROVAL, state.status);
    }

    @Test
    void distinguishesProjectionLagFromAStaleApprovalDigest() {
        TestableTaskService service = new TestableTaskService();
        TaskState state = new TaskState("task-178", "AF-0178", new TaskRequest(
                "http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD));
        state.status = TaskStatus.WAITING_APPROVAL;
        service.memory.save(state);

        var lag = assertThrows(com.example.aifactory.workflow.temporal.TemporalCommandConflictException.class,
                () -> service.approveManifest(state.id,
                        new ManifestApprovalRequest("a".repeat(64), "b".repeat(64))));

        assertEquals(com.example.aifactory.workflow.temporal.TemporalCommandConflictException.Reason.PROJECTION_LAG,
                lag.reason());
    }

    @Test
    void auditsApprovalIntent() {
        HashChainedSecurityAuditJournal journal = new HashChainedSecurityAuditJournal(new byte[32]);
        TestableTaskService service = new TestableTaskService(new InMemoryTaskMemory(), journal);
        TaskState approval = new TaskState("task-approval", "AF-0200", new TaskRequest(
                "http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD));
        approval.status = TaskStatus.WAITING_APPROVAL;
        approval.pendingEffect = new PendingEffect("scm.create_draft_pull_request", java.util.Map.of(),
                "Create PR", "ALLOW", true);
        service.memory.save(approval);
        service.approve(approval.id);

        assertThat(journal.list()).extracting(SecurityAuditJournal.Entry::type)
                .containsExactly(SecurityAuditJournal.EventType.COMMAND_INTENT,
                        SecurityAuditJournal.EventType.COMMAND_ACCEPTED,
                        SecurityAuditJournal.EventType.APPROVAL);
        assertThat(journal.list()).extracting(SecurityAuditJournal.Entry::objectReference)
                .allMatch(reference -> reference.startsWith("attempt-1/APPROVE/"));
        assertThat(journal.verifyIntegrity()).isTrue();
    }

    @Test
    void auditsARejectedCommandWithoutItsFreeFormReason() {
        InMemoryTaskMemory memory = new InMemoryTaskMemory();
        HashChainedSecurityAuditJournal journal = new HashChainedSecurityAuditJournal(new byte[32]);
        WorkflowCoordinator rejecting = new WorkflowCoordinator() {
            @Override public void start(TaskState task) {}
            @Override public void resumeAfterApproval(TaskState task) {}
            @Override public void cancel(TaskState task, com.example.aifactory.model.TaskCancellationRequest request) {
                throw new IllegalStateException("internal detail that must not be audited");
            }
        };
        TaskService service = new TaskService(null, null, rejecting, memory,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), journal,
                reactor.core.publisher.Mono::empty);
        TaskState state = new TaskState("task-audit", "AF-0300", new TaskRequest(
                "http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD));
        memory.save(state);

        assertThrows(IllegalStateException.class, () -> service.cancel(state.id,
                new com.example.aifactory.model.TaskCancellationRequest("sensitive reason", "operator")));

        assertThat(journal.list()).extracting(SecurityAuditJournal.Entry::type).containsExactly(
                SecurityAuditJournal.EventType.COMMAND_INTENT, SecurityAuditJournal.EventType.COMMAND_REJECTED);
        assertThat(journal.list().toString()).doesNotContain("sensitive reason", "internal detail");
        assertThat(journal.verifyIntegrity()).isTrue();
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
            }, memory, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), null,
                    reactor.core.publisher.Mono::empty);
            this.memory = memory;
        }

        private TestableTaskService(InMemoryTaskMemory memory, SecurityAuditJournal audit) {
            super(null, null, new WorkflowCoordinator() {
                @Override public void start(TaskState task) {}
                @Override public void resumeAfterApproval(TaskState task) {}
            }, memory, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), audit,
                    reactor.core.publisher.Mono::empty);
            this.memory = memory;
        }
    }
}
