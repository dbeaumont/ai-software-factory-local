package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aAuthGrant;
import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aExecutionContext;
import com.example.aifactory.a2a.A2aMediaTypes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts AUTH_REQUIRED into an operation-bound, out-of-band credential continuation. */
final class A2aAuthRequiredCoordinator {
    A2aContracts.TaskSnapshot resume(A2aExecutionContext execution, A2aContracts.TaskSnapshot current,
                                     Decision decision, Map<String, Object> executionMetadata,
                                     A2aAuthActivities.ResumeAuth activity) {
        if (current.state() != A2aContracts.TaskState.AUTH_REQUIRED || decision == null
                || decision.actor() == null || decision.actor().isBlank() || decision.grant() == null
                || decision.evidence() == null
                || !A2aMediaTypes.EVIDENCE_REFERENCE.equals(decision.evidence().mediaType())) {
            throw new SecurityException("A2A auth-required continuation lacks an authorized decision");
        }
        A2aAuthGrant grant = decision.grant();
        if (!grant.taskId().equals(current.taskId()) || !grant.contextId().equals(current.contextId())
                || !grant.agentRole().equals(execution.agentRole())) {
            throw new SecurityException("A2A auth grant is not bound to the current task");
        }
        long sequence = ((Number) current.metadata().getOrDefault("sequence", 0)).longValue() + 1;
        String messageId = TemporalIds.sha256(String.join("\n", execution.delegationId(), grant.grantId(),
                Long.toString(sequence), grant.bindingDigest()));
        Map<String, Object> metadata = new LinkedHashMap<>(executionMetadata);
        metadata.put("authGrantId", grant.grantId());
        metadata.put("authGrantBindingDigest", grant.bindingDigest());
        metadata.put("authorizationActor", decision.actor());
        metadata.put("continuationSequence", sequence);
        A2aContracts.SendCommand command = new A2aContracts.SendCommand(grant.agentRole(), grant.operation(),
                messageId, current.taskId(), current.contextId(), List.of(decision.evidence()),
                Map.copyOf(metadata), true);
        return activity.resumeAuth(new A2aAuthActivities.ResumeRequest(execution, command, grant));
    }

    record Decision(String actor, A2aAuthGrant grant, A2aContracts.Part evidence) {}
}
