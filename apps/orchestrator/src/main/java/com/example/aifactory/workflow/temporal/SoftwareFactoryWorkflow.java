package com.example.aifactory.workflow.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.QueryMethod;

import java.util.List;
import java.util.Map;
import java.util.Set;

@WorkflowInterface
public interface SoftwareFactoryWorkflow {
    @WorkflowMethod(name = "SoftwareFactoryWorkflow")
    Result run(Request request);

    @SignalMethod(name = "approve")
    void approve(ApprovalSignal signal);

    @SignalMethod(name = "cancel")
    void cancel(CancellationSignal signal);

    @SignalMethod(name = "humanDecision")
    void decide(HumanDecisionSignal signal);

    @QueryMethod(name = "status")
    String status();

    @QueryMethod(name = "dag")
    List<DelegationView> dag();

    @QueryMethod(name = "budgets")
    Map<String, DelegationWorkflow.Budget> budgets();

    @QueryMethod(name = "evidence")
    List<String> evidence();

    @QueryMethod(name = "pendingEffects")
    List<PendingEffectView> pendingEffects();

    record Request(String taskId, String attemptId, String sourceCommit, String requirement,
                   List<DelegationWorkflow.Request> delegations, ApprovalRequest approvalRequest,
                   List<HumanDecisionRequest> humanDecisionRequests) {
        public Request {
            delegations = delegations == null ? List.of() : List.copyOf(delegations);
            humanDecisionRequests = humanDecisionRequests == null ? List.of() : List.copyOf(humanDecisionRequests);
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement) {
            this(taskId, attemptId, sourceCommit, requirement, List.of(), null, List.of());
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement,
                       List<DelegationWorkflow.Request> delegations) {
            this(taskId, attemptId, sourceCommit, requirement, delegations, null, List.of());
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement,
                       List<DelegationWorkflow.Request> delegations, ApprovalRequest approvalRequest) {
            this(taskId, attemptId, sourceCommit, requirement, delegations, approvalRequest, List.of());
        }
    }

    record Result(String taskId, String attemptId, String sourceCommit, String status, List<String> chronology,
                  List<DelegationWorkflow.Result> delegations, Map<String, String> humanDecisions,
                  String approvedManifestId, String approvedBy, String cancellationReason) {
        public Result {
            chronology = List.copyOf(chronology);
            delegations = List.copyOf(delegations);
            humanDecisions = Map.copyOf(humanDecisions);
        }
    }

    record ApprovalRequest(String manifestId, String uri, String digest) {}

    record ApprovalSignal(String taskId, String attemptId, String manifestId, String manifestDigest,
                          String decision, String approver, String decidedAt) {}

    record HumanDecisionRequest(String decisionId, String question, Set<String> allowedDecisions,
                                List<String> evidenceUris) {
        public HumanDecisionRequest {
            allowedDecisions = Set.copyOf(allowedDecisions);
            evidenceUris = List.copyOf(evidenceUris);
        }
    }

    record HumanDecisionSignal(String taskId, String attemptId, String decisionId, String decision,
                               String actor, String decidedAt) {}

    record CancellationSignal(String taskId, String attemptId, String reason, String actor, String decidedAt) {}

    record DelegationView(String nodeId, String parentNodeId, String role, String status) {}

    record PendingEffectView(String type, String id) {}
}
