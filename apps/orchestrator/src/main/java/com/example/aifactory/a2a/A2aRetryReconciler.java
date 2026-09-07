package com.example.aifactory.a2a;

import com.example.aifactory.config.A2aFleetProperties;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.workflow.temporal.TemporalCommandConflictException;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Fail-closed reconciliation required before an operator can retry an A2A delegation. */
@Component
public final class A2aRetryReconciler {
    private static final Set<WorkflowExecutionStatus> CLOSED_WORKFLOWS = Set.of(
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_COMPLETED,
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_FAILED,
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED,
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED,
            WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TIMED_OUT);

    private final A2aTaskAssociationStore associations;
    private final A2aClient client;
    private final A2aAgentWorkflowProbe workflows;
    private final Duration timeout;
    private final A2aPayloadLimits limits = new A2aPayloadLimits();

    @Autowired
    public A2aRetryReconciler(A2aFleetProperties fleet, A2aTaskAssociationStore associations,
                              A2aClient client, A2aAgentWorkflowProbe workflows) {
        this(associations, client, workflows, fleet.readinessTimeout());
    }

    A2aRetryReconciler(A2aTaskAssociationStore associations, A2aClient client,
                       A2aAgentWorkflowProbe workflows, Duration timeout) {
        this.associations = associations;
        this.client = client;
        this.workflows = workflows;
        this.timeout = timeout;
    }

    public void requireSafeRetry(TaskState task, String delegationId) {
        A2aTaskAssociationStore.Association association = associations.findByDelegation(delegationId)
                .orElseThrow(() -> conflict("A2A retry requires a persisted task association"));
        if (!task.id.equals(association.taskId()) || !task.workflowAttemptId.equals(association.attemptId())) {
            throw conflict("A2A retry association does not match the current workflow attempt");
        }
        if (client == null) throw conflict("A2A retry reconciliation client is unavailable");

        A2aContracts.TaskSnapshot snapshot = getTask(association);
        validateSnapshot(association, snapshot);
        WorkflowExecutionStatus workflowStatus;
        try {
            workflowStatus = workflows.status(association.agentRole(), association.a2aTaskId(), timeout);
        } catch (RuntimeException failure) {
            throw conflict("A2A agent workflow status could not be reconciled", failure);
        }
        if (!CLOSED_WORKFLOWS.contains(workflowStatus)) {
            throw conflict("A2A agent workflow is not closed; retry is unsafe");
        }
        switch (snapshot.state()) {
            case FAILED, CANCELED -> { }
            case COMPLETED -> throw conflict("A2A task already completed; reconcile its result instead of retrying");
            case REJECTED -> throw conflict("A2A task was rejected and is not retryable");
            case SUBMITTED, WORKING, INPUT_REQUIRED, AUTH_REQUIRED ->
                    throw conflict("A2A task is still active; retry is unsafe");
            case UNKNOWN -> throw conflict("A2A task state is unknown; retry is unsafe");
        }
    }

    private A2aContracts.TaskSnapshot getTask(A2aTaskAssociationStore.Association association) {
        try {
            return client.getTask(new A2aContracts.TaskQuery(
                            association.agentRole(), association.a2aTaskId(), 1))
                    .toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            throw conflict("A2A task reconciliation timed out", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw conflict("A2A task reconciliation was interrupted", failure);
        } catch (ExecutionException failure) {
            throw conflict("A2A task could not be reconciled", failure.getCause());
        } catch (RuntimeException failure) {
            throw conflict("A2A task could not be reconciled", failure);
        }
    }

    private void validateSnapshot(A2aTaskAssociationStore.Association association,
                                  A2aContracts.TaskSnapshot snapshot) {
        if (snapshot == null || !association.a2aTaskId().equals(snapshot.taskId())
                || !association.a2aContextId().equals(snapshot.contextId())) {
            throw conflict("A2A task reconciliation returned a different execution");
        }
        try {
            limits.validate(snapshot);
            int evidenceReferences = 0;
            for (A2aContracts.Artifact artifact : snapshot.artifacts()) {
                for (A2aContracts.Part part : artifact.parts()) {
                    if (!A2aMediaTypes.EVIDENCE_REFERENCE.equals(part.mediaType())) continue;
                    evidenceReferences++;
                    if (evidenceReferences > A2aPayloadLimits.MAX_EVIDENCE_REFERENCES) {
                        throw new SecurityException("A2A task has too many Evidence references");
                    }
                    Object schemaVersion = part.data().get("schema_version");
                    Object uri = part.data().get("uri");
                    Object digest = part.data().get("digest");
                    Object contract = part.data().get("contract");
                    if (!"1".equals(schemaVersion) || !(uri instanceof String uriValue)
                            || !(digest instanceof String digestValue) || !(contract instanceof String contractValue)
                            || contractValue.isBlank() || part.uri() == null
                            || !uriValue.equals(part.uri().toString())) {
                        throw new SecurityException("A2A retry Evidence reference is incomplete");
                    }
                    A2aEvidenceUriPolicy.requireBound(uriValue, association.taskId(), association.attemptId(),
                            digestValue);
                }
            }
            if (snapshot.state() == A2aContracts.TaskState.COMPLETED && evidenceReferences == 0) {
                throw new SecurityException("Completed A2A task has no Evidence reference");
            }
        } catch (RuntimeException failure) {
            throw conflict("A2A task artifacts could not be reconciled", failure);
        }
    }

    private static TemporalCommandConflictException conflict(String message) {
        return new TemporalCommandConflictException(
                TemporalCommandConflictException.Reason.RECONCILIATION_REQUIRED, message);
    }

    private static TemporalCommandConflictException conflict(String message, Throwable cause) {
        return new TemporalCommandConflictException(
                TemporalCommandConflictException.Reason.RECONCILIATION_REQUIRED, message, cause);
    }
}
