package com.example.aifactory.agentruntime;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface AgentTaskWorkflowV1 {
    @WorkflowMethod
    Outcome run(Input input);

    @SignalMethod
    void complete(Outcome outcome);

    @SignalMethod
    void cancel(String reason);

    @QueryMethod
    String state();

    record Input(String taskId, String contextId, String role, String skill, String envelopeJson) {}
    record Outcome(String state, String artifactDigest, String detail) {}
}
