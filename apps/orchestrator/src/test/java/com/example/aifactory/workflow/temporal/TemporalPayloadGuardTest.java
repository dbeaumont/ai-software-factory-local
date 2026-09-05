package com.example.aifactory.workflow.temporal;

import io.temporal.client.WorkflowOptions;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

class TemporalPayloadGuardTest {
    @Test
    void acceptsBoundedMetadataAndEvidenceReferences() {
        var request = Map.of(
                "taskId", "task-1",
                "attemptId", "attempt-1",
                "sourceCommit", "a".repeat(40),
                "artifact", Map.of(
                        "uri", "evidence://task-1/attempt-1/code-patch/" + "c".repeat(64),
                        "digest", "c".repeat(64)));

        assertThatCode(() -> TemporalPayloadGuard.requireSafePayload(request)).doesNotThrowAnyException();
    }

    @Test
    void replacesWorkflowNarrativeWithDigestsBeforeSerialization() {
        var root = new SoftwareFactoryWorkflow.Request(
                "task-1", "attempt-1", "a".repeat(40), "confidential product requirement");
        var delegation = new DelegationWorkflow.Request(
                "task-1", "attempt-1", "node-1", null, "developer", "a".repeat(40),
                "inspect internal implementation details");
        var decision = new SoftwareFactoryWorkflow.HumanDecisionRequest(
                "decision-1", "Does the customer accept the security tradeoff?", java.util.Set.of("YES", "NO"),
                java.util.List.of("evidence://task-1/attempt-1/review/" + "b".repeat(64)));

        assertThat(root.requirementDigest()).matches("[0-9a-f]{64}")
                .doesNotContain("product");
        assertThat(delegation.objectiveDigest()).matches("[0-9a-f]{64}")
                .doesNotContain("implementation");
        assertThat(decision.questionDigest()).matches("[0-9a-f]{64}")
                .doesNotContain("customer");
    }

    @Test
    void rejectsSecretsRawPatchesClassifiedTextAndOversizedValues() {
        assertThatThrownBy(() -> TemporalPayloadGuard.requireSafePayload(
                Map.of("api_key", "never-persist"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TemporalPayloadGuard.requireSafePayload(
                Map.of("requirement", "password=hunter2"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TemporalPayloadGuard.requireSafePayload(
                Map.of("patch", "diff --git a/a b/a\n@@ -1 +1 @@\n-secret\n+secret")))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TemporalPayloadGuard.requireSafePayload(
                Map.of("note", "[CONFIDENTIAL] customer data"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TemporalPayloadGuard.requireSafePayload(
                Map.of("repositoryUrl", "https://user:password@example.test/repository.git")))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TemporalPayloadGuard.requireSafePayload(
                Map.of("log", "x".repeat(TemporalPayloadGuard.MAX_SERIALIZED_BYTES))))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsUnreviewedMemoSearchAttributesAndStaticDescriptions() {
        WorkflowOptions memo = WorkflowOptions.newBuilder().setWorkflowId("wf").setTaskQueue("queue")
                .setMemo(Map.of("note", "value")).build();
        WorkflowOptions search = WorkflowOptions.newBuilder().setWorkflowId("wf").setTaskQueue("queue")
                .setSearchAttributes(Map.of("CustomKeywordField", "value")).build();
        WorkflowOptions details = WorkflowOptions.newBuilder().setWorkflowId("wf").setTaskQueue("queue")
                .setStaticDetails("internal details").build();

        assertThatThrownBy(() -> TemporalPayloadGuard.requireSafeStart(memo, Map.of("task", "task-1")))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TemporalPayloadGuard.requireSafeStart(search, Map.of("task", "task-1")))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> TemporalPayloadGuard.requireSafeStart(details, Map.of("task", "task-1")))
                .isInstanceOf(SecurityException.class);
    }
}
