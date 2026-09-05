package com.example.aifactory.workflow.temporal;

/** Stable application error returned when a Temporal command cannot be applied safely. */
public final class TemporalCommandConflictException extends IllegalStateException {
    private final Reason reason;

    public TemporalCommandConflictException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public TemporalCommandConflictException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        WORKFLOW_ABSENT,
        WORKFLOW_TERMINATED,
        APPROVAL_EXPIRED,
        STALE_DIGEST,
        PROJECTION_LAG
    }
}
