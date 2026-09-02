package com.example.aifactory.workflow.temporal;

import java.util.List;
import java.util.ArrayList;

import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;

/** Root durable workflow; subsequent migration steps add child workflows and activities. */
public final class SoftwareFactoryWorkflowImpl implements SoftwareFactoryWorkflow {
    private Request request;
    private ApprovalSignal receivedApproval;

    @Override
    public Result run(Request request) {
        requireValid(request);
        this.request = request;
        List<String> chronology = new ArrayList<>();
        chronology.add("WORKFLOW_STARTED");
        List<DelegationWorkflow.Result> results = new ArrayList<>();
        for (DelegationWorkflow.Request delegation : request.delegations()) {
            DelegationWorkflow child = Workflow.newChildWorkflowStub(DelegationWorkflow.class,
                    ChildWorkflowOptions.newBuilder().setWorkflowId(delegationId(request, delegation)).build());
            DelegationWorkflow.Result result = child.run(delegation);
            results.add(result);
            chronology.add("DELEGATION_COMPLETED:" + result.nodeId());
        }
        if (request.approvalRequest() == null) {
            return new Result(request.taskId(), request.attemptId(), request.sourceCommit(),
                    results.isEmpty() ? "READY_FOR_DELEGATION" : "DELEGATIONS_COMPLETED",
                    chronology, results, null, null);
        }
        requireManifest(request.approvalRequest());
        chronology.add("WAITING_APPROVAL:" + request.approvalRequest().manifestId());
        Workflow.await(() -> approvalMatches(receivedApproval));
        chronology.add("APPROVED:" + request.approvalRequest().manifestId());
        return new Result(request.taskId(), request.attemptId(), request.sourceCommit(), "APPROVED",
                chronology, results, request.approvalRequest().manifestId(), receivedApproval.approver());
    }

    @Override
    public void approve(ApprovalSignal signal) {
        receivedApproval = signal;
    }

    private static void requireValid(Request request) {
        if (request == null || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.requirement() == null || request.requirement().isBlank()) {
            throw new IllegalArgumentException("Software factory workflow request is invalid");
        }
    }

    private static void requireManifest(ApprovalRequest manifest) {
        if (manifest.manifestId() == null || !manifest.manifestId().matches("[0-9a-f]{64}")
                || manifest.digest() == null || !manifest.digest().matches("[0-9a-f]{64}")
                || manifest.uri() == null || !manifest.uri().startsWith("evidence://")) {
            throw new IllegalArgumentException("Approval manifest is invalid");
        }
    }

    private boolean approvalMatches(ApprovalSignal approval) {
        ApprovalRequest manifest = request == null ? null : request.approvalRequest();
        return approval != null && manifest != null && request.taskId().equals(approval.taskId())
                && request.attemptId().equals(approval.attemptId())
                && manifest.manifestId().equals(approval.manifestId())
                && manifest.digest().equals(approval.manifestDigest())
                && "APPROVE".equals(approval.decision())
                && approval.approver() != null && !approval.approver().isBlank()
                && approval.decidedAt() != null && !approval.decidedAt().isBlank();
    }

    private static String delegationId(Request root, DelegationWorkflow.Request child) {
        return TemporalIds.delegation(root.taskId(), root.attemptId(), child.nodeId());
    }
}
