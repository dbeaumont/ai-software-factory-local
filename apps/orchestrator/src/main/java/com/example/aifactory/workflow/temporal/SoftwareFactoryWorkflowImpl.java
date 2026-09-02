package com.example.aifactory.workflow.temporal;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;

/** Root durable workflow; subsequent migration steps add child workflows and activities. */
public final class SoftwareFactoryWorkflowImpl implements SoftwareFactoryWorkflow {
    private Request request;
    private ApprovalSignal receivedApproval;
    private CancellationSignal receivedCancellation;
    private final Map<String, HumanDecisionSignal> receivedDecisions = new LinkedHashMap<>();

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
        Map<String, String> decisions = new LinkedHashMap<>();
        for (HumanDecisionRequest decision : request.humanDecisionRequests()) {
            requireDecision(decision);
            chronology.add("WAITING_DECISION:" + decision.decisionId());
            Workflow.await(() -> cancelled() || decisionMatches(decision));
            if (cancelled()) return cancelledResult(request, chronology, results, decisions);
            HumanDecisionSignal received = receivedDecisions.get(decision.decisionId());
            decisions.put(decision.decisionId(), received.decision());
            chronology.add("DECISION_RECORDED:" + decision.decisionId() + ':' + received.decision());
        }
        if (request.approvalRequest() == null) {
            return new Result(request.taskId(), request.attemptId(), request.sourceCommit(),
                    decisions.isEmpty() ? (results.isEmpty() ? "READY_FOR_DELEGATION" : "DELEGATIONS_COMPLETED")
                            : "DECISIONS_COMPLETED",
                    chronology, results, decisions, null, null, null);
        }
        requireManifest(request.approvalRequest());
        chronology.add("WAITING_APPROVAL:" + request.approvalRequest().manifestId());
        Workflow.await(() -> cancelled() || approvalMatches(receivedApproval));
        if (cancelled()) return cancelledResult(request, chronology, results, decisions);
        chronology.add("APPROVED:" + request.approvalRequest().manifestId());
        return new Result(request.taskId(), request.attemptId(), request.sourceCommit(), "APPROVED",
                chronology, results, decisions, request.approvalRequest().manifestId(), receivedApproval.approver(), null);
    }

    @Override
    public void approve(ApprovalSignal signal) {
        receivedApproval = signal;
    }

    @Override
    public void cancel(CancellationSignal signal) {
        receivedCancellation = signal;
    }

    @Override
    public void decide(HumanDecisionSignal signal) {
        if (signal != null && signal.decisionId() != null) receivedDecisions.put(signal.decisionId(), signal);
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

    private boolean cancelled() {
        return receivedCancellation != null && request != null
                && request.taskId().equals(receivedCancellation.taskId())
                && request.attemptId().equals(receivedCancellation.attemptId())
                && receivedCancellation.reason() != null && !receivedCancellation.reason().isBlank()
                && receivedCancellation.actor() != null && !receivedCancellation.actor().isBlank()
                && receivedCancellation.decidedAt() != null && !receivedCancellation.decidedAt().isBlank();
    }

    private boolean decisionMatches(HumanDecisionRequest expected) {
        HumanDecisionSignal signal = receivedDecisions.get(expected.decisionId());
        return signal != null && request.taskId().equals(signal.taskId())
                && request.attemptId().equals(signal.attemptId())
                && expected.allowedDecisions().contains(signal.decision())
                && signal.actor() != null && !signal.actor().isBlank()
                && signal.decidedAt() != null && !signal.decidedAt().isBlank();
    }

    private static void requireDecision(HumanDecisionRequest decision) {
        if (decision == null || decision.decisionId() == null
                || !decision.decisionId().matches("[A-Za-z0-9_-]{1,128}")
                || decision.question() == null || decision.question().isBlank()
                || decision.allowedDecisions().isEmpty()) {
            throw new IllegalArgumentException("Human decision request is invalid");
        }
    }

    private Result cancelledResult(Request request, List<String> chronology,
                                   List<DelegationWorkflow.Result> results, Map<String, String> decisions) {
        chronology.add("CANCELLED");
        return new Result(request.taskId(), request.attemptId(), request.sourceCommit(), "CANCELLED",
                chronology, results, decisions, null, null, receivedCancellation.reason());
    }

    private static String delegationId(Request root, DelegationWorkflow.Request child) {
        return TemporalIds.delegation(root.taskId(), root.attemptId(), child.nodeId());
    }
}
