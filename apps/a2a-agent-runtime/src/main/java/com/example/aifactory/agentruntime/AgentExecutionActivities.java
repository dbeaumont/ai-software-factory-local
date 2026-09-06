package com.example.aifactory.agentruntime;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.Set;

/** Executes one validated A2A instruction outside deterministic workflow code. */
@ActivityInterface
public interface AgentExecutionActivities {
    @ActivityMethod
    Result execute(Command command);

    record Command(String taskId, String role, String skill,
                   String envelopeJson, String traceparent, String baggage) {}

    record Result(String attemptId, String outputContract, Set<String> allowedReferenceIds,
                  String artifactContentBase64, String artifactDigest) {
        public Result {
            allowedReferenceIds = allowedReferenceIds == null ? Set.of() : Set.copyOf(allowedReferenceIds);
        }
    }
}
