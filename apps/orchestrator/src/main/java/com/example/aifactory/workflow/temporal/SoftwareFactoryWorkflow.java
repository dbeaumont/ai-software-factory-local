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

    record Request(String taskId, String attemptId, String repositoryId, String sourceCommit, String requirement,
                   List<DelegationWorkflow.Request> delegations, ApprovalRequest approvalRequest,
                   List<HumanDecisionRequest> humanDecisionRequests, ExecutionPolicy executionPolicy,
                   ContinuationState continuationState, IndependentReviewWorkflow.Request independentReview) {
        public Request {
            delegations = delegations == null ? List.of() : List.copyOf(delegations);
            humanDecisionRequests = humanDecisionRequests == null ? List.of() : List.copyOf(humanDecisionRequests);
            executionPolicy = executionPolicy == null ? ExecutionPolicy.defaults() : executionPolicy;
            continuationState = continuationState == null ? ContinuationState.initial() : continuationState;
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement) {
            this(taskId, attemptId, "legacy", sourceCommit, requirement, List.of(), null, List.of(), null, null, null);
        }

        public Request(String taskId, String attemptId, String repositoryId, String sourceCommit,
                       String requirement) {
            this(taskId, attemptId, repositoryId, sourceCommit, requirement,
                    List.of(), null, List.of(), null, null, null);
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement,
                       List<DelegationWorkflow.Request> delegations) {
            this(taskId, attemptId, "legacy", sourceCommit, requirement,
                    delegations, null, List.of(), null, null, null);
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement,
                       List<DelegationWorkflow.Request> delegations, ApprovalRequest approvalRequest) {
            this(taskId, attemptId, "legacy", sourceCommit, requirement,
                    delegations, approvalRequest, List.of(), null, null, null);
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement,
                       List<DelegationWorkflow.Request> delegations, ApprovalRequest approvalRequest,
                       List<HumanDecisionRequest> humanDecisionRequests) {
            this(taskId, attemptId, "legacy", sourceCommit, requirement, delegations, approvalRequest,
                    humanDecisionRequests, null, null, null);
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement,
                       List<DelegationWorkflow.Request> delegations, ApprovalRequest approvalRequest,
                       List<HumanDecisionRequest> humanDecisionRequests, ExecutionPolicy executionPolicy) {
            this(taskId, attemptId, "legacy", sourceCommit, requirement, delegations, approvalRequest,
                    humanDecisionRequests, executionPolicy, null, null);
        }

        public Request(String taskId, String attemptId, String repositoryId, String sourceCommit,
                       String requirement, List<DelegationWorkflow.Request> delegations,
                       ApprovalRequest approvalRequest, List<HumanDecisionRequest> humanDecisionRequests,
                       ExecutionPolicy executionPolicy, ContinuationState continuationState) {
            this(taskId, attemptId, repositoryId, sourceCommit, requirement, delegations, approvalRequest,
                    humanDecisionRequests, executionPolicy, continuationState, null);
        }

        Request continuedWith(ContinuationState state) {
            return new Request(taskId, attemptId, repositoryId, sourceCommit, requirement, delegations, approvalRequest,
                    humanDecisionRequests, executionPolicy, state, independentReview);
        }
    }

    record ExecutionPolicy(long maxHistoryEvents, long maxHistoryBytes, int maxDelegationsPerRun) {
        private static final long DEFAULT_MAX_HISTORY_EVENTS = 10_000;
        private static final long DEFAULT_MAX_HISTORY_BYTES = 10_000_000;
        private static final int DEFAULT_MAX_DELEGATIONS_PER_RUN = 100;

        public ExecutionPolicy {
            if (maxHistoryEvents < 1 || maxHistoryBytes < 1 || maxDelegationsPerRun < 1) {
                throw new IllegalArgumentException("Workflow execution policy is invalid");
            }
        }

        static ExecutionPolicy defaults() {
            return new ExecutionPolicy(DEFAULT_MAX_HISTORY_EVENTS, DEFAULT_MAX_HISTORY_BYTES,
                    DEFAULT_MAX_DELEGATIONS_PER_RUN);
        }
    }

    record ContinuationState(int nextDelegationIndex, int generation,
                             List<DelegationWorkflow.Result> delegations, List<String> chronology,
                             Map<String, HumanDecisionSignal> receivedDecisions,
                             ApprovalSignal receivedApproval, CancellationSignal receivedCancellation) {
        public ContinuationState {
            delegations = delegations == null ? List.of() : List.copyOf(delegations);
            chronology = chronology == null ? List.of() : List.copyOf(chronology);
            receivedDecisions = receivedDecisions == null ? Map.of() : Map.copyOf(receivedDecisions);
        }

        static ContinuationState initial() {
            return new ContinuationState(0, 0, List.of(), List.of(), Map.of(), null, null);
        }
    }

    record Result(String taskId, String attemptId, String sourceCommit, String status, List<String> chronology,
                  List<DelegationWorkflow.Result> delegations, Map<String, String> humanDecisions,
                  String approvedManifestId, String approvedBy, String cancellationReason,
                  IndependentReviewWorkflow.Result independentReview) {
        public Result {
            chronology = List.copyOf(chronology);
            delegations = List.copyOf(delegations);
            humanDecisions = Map.copyOf(humanDecisions);
        }

        public Result(String taskId, String attemptId, String sourceCommit, String status,
                      List<String> chronology, List<DelegationWorkflow.Result> delegations,
                      Map<String, String> humanDecisions, String approvedManifestId,
                      String approvedBy, String cancellationReason) {
            this(taskId, attemptId, sourceCommit, status, chronology, delegations, humanDecisions,
                    approvedManifestId, approvedBy, cancellationReason, null);
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
