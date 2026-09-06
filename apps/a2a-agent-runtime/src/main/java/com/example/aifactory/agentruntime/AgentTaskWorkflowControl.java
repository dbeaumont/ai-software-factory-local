package com.example.aifactory.agentruntime;

/** Boundary used by A2A task operations to signal the durable agent workflow. */
public interface AgentTaskWorkflowControl {
    void requestCancellation(String taskId, String contextId, String reason);

    default void requestContinuation(String taskId, String contextId, String messageId, String envelopeJson) {
        throw new UnsupportedOperationException("Task continuation is unavailable");
    }
}
