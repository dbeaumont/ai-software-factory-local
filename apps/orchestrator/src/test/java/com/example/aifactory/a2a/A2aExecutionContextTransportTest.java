package com.example.aifactory.a2a;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aExecutionContextTransportTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void roundTripsEveryRequiredCorrelationFieldUnderTheOfficialExtensionId() {
        A2aExecutionContext context = context();
        Map<String, Object> metadata = context.asMetadata(mapper);
        assertThat(metadata).containsOnlyKeys(A2aExtensions.EXECUTION_CONTEXT_V1);
        assertThat(A2aExecutionContext.fromMetadata(metadata, mapper)).isEqualTo(context);
        @SuppressWarnings("unchecked") Map<String, Object> extension =
                (Map<String, Object>) metadata.get(A2aExtensions.EXECUTION_CONTEXT_V1);
        assertThat(extension).containsKeys(
                "schemaVersion", "taskId", "attemptId", "workflowId", "workflowRunId", "repositoryId",
                "sourceCommit", "delegationId", "parentDelegationId", "agentRole", "inputDigests");
    }

    @Test
    void rejectsMissingExtensionUnknownRoleBadCommitAndDuplicateDigest() {
        assertThatThrownBy(() -> A2aExecutionContext.fromMetadata(Map.of(), mapper)).hasMessageContaining("Missing");
        A2aExecutionContext valid = context();
        assertThatThrownBy(() -> copy(valid, valid.sourceCommit(), "workflow", valid.inputDigests()))
                .hasMessageContaining("role");
        assertThatThrownBy(() -> copy(valid, "short", valid.agentRole(), valid.inputDigests()))
                .hasMessageContaining("commit");
        assertThatThrownBy(() -> copy(valid, valid.sourceCommit(), valid.agentRole(),
                List.of("b".repeat(64), "b".repeat(64)))).hasMessageContaining("unique");
    }

    @Test
    void extensionCanTravelAlongsideProtocolMetadataWithoutMutation() {
        Map<String, Object> metadata = new LinkedHashMap<>(context().asMetadata(mapper));
        metadata.put("https://example.invalid/optional-extension", Map.of("value", true));
        assertThat(A2aExecutionContext.fromMetadata(Map.copyOf(metadata), mapper)).isEqualTo(context());
    }

    private static A2aExecutionContext copy(A2aExecutionContext value, String commit, String role,
                                            List<String> digests) {
        return new A2aExecutionContext("1", value.taskId(), value.attemptId(), value.workflowId(),
                value.workflowRunId(), value.repositoryId(), commit, value.delegationId(),
                value.parentDelegationId(), role, digests);
    }

    private static A2aExecutionContext context() {
        return new A2aExecutionContext("1", "task-1", "attempt-1", "ai-factory/task-1/attempt-1",
                "run-8f8c", "customer-api", "a".repeat(40), "delegation-1", "delegation-root",
                "developer", List.of("b".repeat(64)));
    }
}
