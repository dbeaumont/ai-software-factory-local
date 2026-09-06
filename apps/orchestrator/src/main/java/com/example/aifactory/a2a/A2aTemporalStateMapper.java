package com.example.aifactory.a2a;

import java.util.Objects;

/** Maps protocol task states to deterministic Temporal coordination decisions. */
public final class A2aTemporalStateMapper {

    public TemporalTransition map(A2aContracts.TaskState state) {
        Objects.requireNonNull(state, "state");
        return switch (state) {
            case SUBMITTED -> new TemporalTransition(
                    "A2A_TASK_ASSOCIATED", "WAIT_NON_BLOCKING", false, FailureKind.NONE);
            case WORKING -> new TemporalTransition(
                    "A2A_HEARTBEAT", "RECORD_PROGRESS", false, FailureKind.NONE);
            case INPUT_REQUIRED -> new TemporalTransition(
                    "A2A_INPUT_REQUIRED", "SIGNAL_CONTROLLED_INPUT", false, FailureKind.NONE);
            case AUTH_REQUIRED -> new TemporalTransition(
                    "A2A_AUTH_REQUIRED", "SUSPEND_AND_ESCALATE", false, FailureKind.AUTHORIZATION);
            case COMPLETED -> new TemporalTransition(
                    "A2A_TASK_COMPLETED", "VALIDATE_ARTIFACTS_AND_RESUME", true, FailureKind.NONE);
            case REJECTED -> new TemporalTransition(
                    "A2A_TASK_REJECTED", "BUSINESS_REJECTION_NO_RETRY", true, FailureKind.BUSINESS);
            case FAILED -> new TemporalTransition(
                    "A2A_TASK_FAILED", "CLASSIFY_BEFORE_RETRY", true, FailureKind.UNCLASSIFIED);
            case CANCELED -> new TemporalTransition(
                    "A2A_TASK_CANCELED", "CONFIRM_IDEMPOTENT_CANCELLATION", true, FailureKind.CANCELLATION);
            case UNKNOWN -> throw new IllegalArgumentException("Unknown A2A task state cannot drive Temporal");
        };
    }

    public enum FailureKind {
        NONE,
        BUSINESS,
        AUTHORIZATION,
        CANCELLATION,
        UNCLASSIFIED
    }

    public record TemporalTransition(
            String eventType,
            String workflowAction,
            boolean terminal,
            FailureKind failureKind) {
        public TemporalTransition {
            if (eventType == null || eventType.isBlank()) {
                throw new IllegalArgumentException("eventType must not be blank");
            }
            if (workflowAction == null || workflowAction.isBlank()) {
                throw new IllegalArgumentException("workflowAction must not be blank");
            }
            Objects.requireNonNull(failureKind, "failureKind");
        }
    }
}
