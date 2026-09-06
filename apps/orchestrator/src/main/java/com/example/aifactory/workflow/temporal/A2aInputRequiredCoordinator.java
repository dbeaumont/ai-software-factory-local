package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aExecutionContext;
import com.example.aifactory.a2a.A2aMediaTypes;

import java.util.List;
import java.util.Map;

/** Resumes INPUT_REQUIRED only from an authorized, evidence-bound business decision. */
final class A2aInputRequiredCoordinator {

    A2aContracts.TaskSnapshot resume(A2aExecutionContext execution, String skillId,
                                     A2aContracts.TaskSnapshot current, Decision decision,
                                     Map<String, Object> executionMetadata,
                                     A2aActivities.ContinueTask continuation) {
        if (current.state() != A2aContracts.TaskState.INPUT_REQUIRED || decision == null || !decision.approved()
                || decision.actor() == null || decision.actor().isBlank()
                || decision.reference() == null
                || !A2aMediaTypes.EVIDENCE_REFERENCE.equals(decision.reference().mediaType())) {
            throw new SecurityException("A2A input-required continuation lacks an authorized gate decision");
        }
        String digest = String.valueOf(decision.reference().data().get("digest"));
        if (!digest.matches("[0-9a-f]{64}")) {
            throw new SecurityException("A2A continuation evidence digest is invalid");
        }
        long nextSequence = ((Number) current.metadata().getOrDefault("sequence", 0)).longValue() + 1;
        String messageId = TemporalIds.sha256(String.join("\n", execution.delegationId(),
                current.taskId(), Long.toString(nextSequence), digest));
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(executionMetadata);
        metadata.put("gateActor", decision.actor());
        metadata.put("continuationSequence", nextSequence);
        A2aContracts.SendCommand command = new A2aContracts.SendCommand(execution.agentRole(), skillId, messageId,
                current.taskId(), current.contextId(), List.of(decision.reference()), Map.copyOf(metadata), true);
        return continuation.continueTask(new A2aActivities.ContinuationRequest(execution, command));
    }

    record Decision(boolean approved, String actor, A2aContracts.Part reference) {}
}
