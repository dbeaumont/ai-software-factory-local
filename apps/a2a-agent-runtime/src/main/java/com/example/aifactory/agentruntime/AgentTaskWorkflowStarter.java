package com.example.aifactory.agentruntime;

/** Starts exactly one durable workflow for an accepted A2A task. */
public interface AgentTaskWorkflowStarter {
    Execution start(A2aSendMessageService.Submission submission, String envelopeJson);

    record Execution(String workflowId, String runId) {}
}
