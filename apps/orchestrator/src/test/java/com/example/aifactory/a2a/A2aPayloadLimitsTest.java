package com.example.aifactory.a2a;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aPayloadLimitsTest {
    private final A2aPayloadLimits limits = new A2aPayloadLimits();

    @Test
    void rejectsOversizeBeforeJsonDeserializationAndDeepJsonDuringBoundedParsing() {
        byte[] oversized = new byte[A2aPayloadLimits.MAX_REQUEST_BYTES + 1];
        assertThatThrownBy(() -> limits.parse(oversized))
                .isInstanceOfSatisfying(A2aPayloadLimits.PayloadLimitException.class,
                        failure -> assertThat(failure.limit()).isEqualTo("request_bytes"));

        String deep = "[".repeat(A2aPayloadLimits.MAX_JSON_DEPTH + 1) + "0"
                + "]".repeat(A2aPayloadLimits.MAX_JSON_DEPTH + 1);
        assertThatThrownBy(() -> limits.parse(deep.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(A2aPayloadLimits.PayloadLimitException.class,
                        failure -> assertThat(failure.limit()).isEqualTo("json_structure"));
    }

    @Test
    void enforcesPartsHistoryArtifactsAndRetentionBounds() {
        A2aContracts.Part part = new A2aContracts.Part(A2aMediaTypes.TEXT, "x", Map.of(), null);
        assertThatThrownBy(() -> limits.validate(new A2aContracts.SendCommand(
                "developer", "developer.code-task-v1", "message-1", null, null,
                java.util.Collections.nCopies(17, part), Map.of(), true)))
                .isInstanceOf(A2aPayloadLimits.PayloadLimitException.class);
        assertThatThrownBy(() -> limits.validate(new A2aContracts.TaskQuery("developer", "task-1", 51)))
                .isInstanceOf(A2aPayloadLimits.PayloadLimitException.class);
        A2aContracts.Artifact artifact = new A2aContracts.Artifact("a", "a", List.of(part), Map.of());
        assertThatThrownBy(() -> limits.validate(new A2aContracts.TaskSnapshot(
                "task-1", "context-1", A2aContracts.TaskState.COMPLETED, Instant.now(),
                java.util.Collections.nCopies(17, artifact), Map.of())))
                .isInstanceOf(A2aPayloadLimits.PayloadLimitException.class);
        assertThat(A2aPayloadLimits.MAX_EVIDENCE_REFERENCES).isEqualTo(32);
        assertThat(A2aPayloadLimits.TASK_RETENTION).isEqualTo(java.time.Duration.ofDays(30));
    }
}
