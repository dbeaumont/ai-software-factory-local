package com.example.aifactory.agentruntime;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

import java.util.Set;

@WorkflowInterface
public interface AgentTaskWorkflowV1 {
    @WorkflowMethod
    Outcome run(Input input);

    @SignalMethod
    void complete(Outcome outcome);

    @SignalMethod
    void cancel(String reason);

    @SignalMethod
    void continueWith(String messageId, String envelopeJson);

    @QueryMethod
    String state();

    record Input(String taskId, String contextId, String role, String skill, String envelopeJson) {}
    record Outcome(String state, String artifactDigest, String detail, String attemptId,
                   String outputContract, Set<String> allowedReferenceIds, String artifactContentBase64) {
        public Outcome {
            allowedReferenceIds = allowedReferenceIds == null ? Set.of() : Set.copyOf(allowedReferenceIds);
        }

        public Outcome(String state, String artifactDigest, String detail) {
            this(state, artifactDigest, detail, null, null, Set.of(), null);
        }
    }
}
