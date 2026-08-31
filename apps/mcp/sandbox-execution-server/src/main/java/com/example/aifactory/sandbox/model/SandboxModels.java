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

    public record StartExecutionRequest(
            String schemaVersion,
            String taskId,
            String sourceCommit,
            String actor,
            String traceId,
            String idempotencyKey,
            String patchDigest) {
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
            String error,
            @JsonProperty("created_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant createdAt,
            @JsonProperty("started_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant startedAt,
            @JsonProperty("completed_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant completedAt,
            @JsonProperty("heartbeat_at") @JsonFormat(shape = JsonFormat.Shape.STRING) Instant heartbeatAt) {
    }
}
