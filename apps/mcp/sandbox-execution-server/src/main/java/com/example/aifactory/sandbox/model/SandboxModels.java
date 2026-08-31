package com.example.aifactory.sandbox.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

public final class SandboxModels {
    private SandboxModels() {
    }

    public enum Operation {
        VALIDATE_PATCH,
        APPLY_PATCH,
        RUN_TESTS,
        RUN_QUALITY,
        RUN_SECURITY
    }

    public enum ExecutionStatus {
        ACCEPTED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        TIMED_OUT,
        CANCELLED
    }

    public enum Verdict {
        PENDING,
        PASSED,
        REJECTED,
        INDETERMINATE
    }

    public enum EvidenceStatus {
        NONE,
        PARTIAL,
        COMPLETE
    }

    public record StartExecutionRequest(
            String schemaVersion,
            String taskId,
            String attemptId,
            String sourceCommit,
            String actor,
            String traceId,
            String traceparent,
            String deadline,
            String idempotencyKey,
            String patchDigest) {
        public StartExecutionRequest(String schemaVersion, String taskId, String sourceCommit, String actor,
                                     String traceId, String idempotencyKey, String patchDigest) {
            this(schemaVersion, taskId, "attempt-test", sourceCommit, actor, traceId,
                    "00-" + traceId + "-0123456789abcdef-01", Instant.now().plusSeconds(60).toString(),
                    idempotencyKey, patchDigest);
        }
    }

    public record ExecutionView(
            @JsonProperty("execution_id") String executionId,
            @JsonProperty("task_id") String taskId,
            Operation operation,
            ExecutionStatus status,
            Verdict verdict,
            @JsonProperty("exit_code") Integer exitCode,
            String output,
            @JsonProperty("output_cursor") int outputCursor,
            @JsonProperty("next_output_cursor") Integer nextOutputCursor,
            @JsonProperty("output_total_chars") int outputTotalChars,
            @JsonProperty("output_truncated") boolean outputTruncated,
            @JsonProperty("evidence_status") EvidenceStatus evidenceStatus,
            @JsonProperty("output_digest") String outputDigest,
            String error,
            @JsonProperty("created_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
            @JsonProperty("started_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant startedAt,
            @JsonProperty("completed_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant completedAt,
            @JsonProperty("heartbeat_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant heartbeatAt) {
    }
}
