package com.example.aifactory.workflow.projection;

/** Stable operator-facing outcome when Temporal retention has removed an execution history. */
public final class ProjectionHistoryUnavailableException extends RuntimeException {
    public ProjectionHistoryUnavailableException(String workflowId, String runId, Throwable cause) {
        super("Temporal history is unavailable or expired for workflow=" + workflowId + " run=" + runId, cause);
    }
}
