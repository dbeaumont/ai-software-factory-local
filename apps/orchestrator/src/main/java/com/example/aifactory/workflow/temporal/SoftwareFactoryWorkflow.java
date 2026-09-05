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

    record Request(String taskId, String attemptId, String repositoryId, String sourceCommit, String requirementDigest,
                   List<DelegationWorkflow.Request> delegations, ApprovalRequest approvalRequest,
                   List<HumanDecisionRequest> humanDecisionRequests, ExecutionPolicy executionPolicy,
                   ContinuationState continuationState, IndependentReviewWorkflow.Request independentReview,
                   SourceLocation sourceLocation, WorkflowExecutionMode executionMode,
                   AttemptLineage attemptLineage) {
        public Request {
            requirementDigest = digestText(requirementDigest);
            delegations = delegations == null ? List.of() : List.copyOf(delegations);
            humanDecisionRequests = humanDecisionRequests == null ? List.of() : List.copyOf(humanDecisionRequests);
            executionPolicy = executionPolicy == null ? ExecutionPolicy.defaults() : executionPolicy;
            continuationState = continuationState == null ? ContinuationState.initial() : continuationState;
            executionMode = executionMode == null ? WorkflowExecutionMode.PIPELINE : executionMode;
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement) {
            this(taskId, attemptId, "legacy", sourceCommit, requirement, List.of(), null, List.of(), null, null, null, null, null, null);
        }

        public Request(String taskId, String attemptId, String repositoryId, String sourceCommit,
                       String requirement) {
            this(taskId, attemptId, repositoryId, sourceCommit, requirement,
                    List.of(), null, List.of(), null, null, null, null, null, null);
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement,
                       List<DelegationWorkflow.Request> delegations) {
            this(taskId, attemptId, "legacy", sourceCommit, requirement,
                    delegations, null, List.of(), null, null, null, null, null, null);
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement,
                       List<DelegationWorkflow.Request> delegations, ApprovalRequest approvalRequest) {
            this(taskId, attemptId, "legacy", sourceCommit, requirement,
                    delegations, approvalRequest, List.of(), null, null, null, null, null, null);
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement,
                       List<DelegationWorkflow.Request> delegations, ApprovalRequest approvalRequest,
                       List<HumanDecisionRequest> humanDecisionRequests) {
            this(taskId, attemptId, "legacy", sourceCommit, requirement, delegations, approvalRequest,
                    humanDecisionRequests, null, null, null, null, null, null);
        }

        public Request(String taskId, String attemptId, String sourceCommit, String requirement,
                       List<DelegationWorkflow.Request> delegations, ApprovalRequest approvalRequest,
                       List<HumanDecisionRequest> humanDecisionRequests, ExecutionPolicy executionPolicy) {
            this(taskId, attemptId, "legacy", sourceCommit, requirement, delegations, approvalRequest,
                    humanDecisionRequests, executionPolicy, null, null, null, null, null);
        }

        public Request(String taskId, String attemptId, String repositoryId, String sourceCommit,
                       String requirement, List<DelegationWorkflow.Request> delegations,
                       ApprovalRequest approvalRequest, List<HumanDecisionRequest> humanDecisionRequests,
                       ExecutionPolicy executionPolicy, ContinuationState continuationState) {
            this(taskId, attemptId, repositoryId, sourceCommit, requirement, delegations, approvalRequest,
                    humanDecisionRequests, executionPolicy, continuationState, null, null, null, null);
        }

        public Request(String taskId, String attemptId, String repositoryId, String sourceCommit,
                       String requirement, List<DelegationWorkflow.Request> delegations,
                       ApprovalRequest approvalRequest, List<HumanDecisionRequest> humanDecisionRequests,
                       ExecutionPolicy executionPolicy, ContinuationState continuationState,
                       IndependentReviewWorkflow.Request independentReview) {
            this(taskId, attemptId, repositoryId, sourceCommit, requirement, delegations, approvalRequest,
                    humanDecisionRequests, executionPolicy, continuationState, independentReview, null, null, null);
        }

        public Request(String taskId, String attemptId, String repositoryId, String sourceCommit,
                       String requirement, List<DelegationWorkflow.Request> delegations,
                       ApprovalRequest approvalRequest, List<HumanDecisionRequest> humanDecisionRequests,
                       ExecutionPolicy executionPolicy, ContinuationState continuationState,
                       IndependentReviewWorkflow.Request independentReview, SourceLocation sourceLocation) {
            this(taskId, attemptId, repositoryId, sourceCommit, requirement, delegations, approvalRequest,
                    humanDecisionRequests, executionPolicy, continuationState, independentReview, sourceLocation,
                    WorkflowExecutionMode.PIPELINE, null);
        }

        public Request(String taskId, String attemptId, String repositoryId, String sourceCommit,
                       String requirement, List<DelegationWorkflow.Request> delegations,
                       ApprovalRequest approvalRequest, List<HumanDecisionRequest> humanDecisionRequests,
                       ExecutionPolicy executionPolicy, ContinuationState continuationState,
                       IndependentReviewWorkflow.Request independentReview, SourceLocation sourceLocation,
                       WorkflowExecutionMode executionMode) {
            this(taskId, attemptId, repositoryId, sourceCommit, requirement, delegations, approvalRequest,
                    humanDecisionRequests, executionPolicy, continuationState, independentReview, sourceLocation,
                    executionMode, null);
        }

        Request continuedWith(ContinuationState state) {
            return new Request(taskId, attemptId, repositoryId, sourceCommit, requirementDigest, delegations, approvalRequest,
                    humanDecisionRequests, executionPolicy, state, independentReview, sourceLocation, executionMode,
                    attemptLineage);
        }

        public Request withResolvedSource(String commit) {
            return new Request(taskId, attemptId, repositoryId, commit, requirementDigest, delegations, approvalRequest,
                    humanDecisionRequests, executionPolicy, continuationState, independentReview, sourceLocation,
                    executionMode, attemptLineage);
        }

        Request withApprovalRequest(ApprovalRequest approval) {
            return new Request(taskId, attemptId, repositoryId, sourceCommit, requirementDigest, delegations, approval,
                    humanDecisionRequests, executionPolicy, continuationState, independentReview, sourceLocation,
                    executionMode, attemptLineage);
        }
    }

    enum WorkflowExecutionMode { PIPELINE, HIERARCHICAL_ACTIVE }

    record AttemptLineage(String previousAttemptId, String reasonDigest, String actor) {
        public AttemptLineage {
            if (previousAttemptId == null || !previousAttemptId.matches("[A-Za-z0-9_-]{1,128}")
                    || reasonDigest == null || !reasonDigest.matches("[0-9a-f]{64}")
                    || actor == null || actor.isBlank() || actor.length() > 256) {
                throw new IllegalArgumentException("Workflow attempt lineage is invalid");
            }
        }
    }

    record SourceLocation(String repositoryUrl, String branch, String contextTaskQueue,
                          Map<String, String> taskQueues) {
        public SourceLocation {
            taskQueues = taskQueues == null ? Map.of() : Map.copyOf(taskQueues);
        }

        public SourceLocation(String repositoryUrl, String branch, String contextTaskQueue) {
            this(repositoryUrl, branch, contextTaskQueue, Map.of(
                    "context", contextTaskQueue, "llm", "ai-factory-llm", "sandbox", "ai-factory-sandbox",
                    "assurance", "ai-factory-assurance", "evidence", "ai-factory-evidence", "scm", "ai-factory-scm"));
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
                  String approvedManifestId, String approvedBy, String cancellationReasonDigest,
                  IndependentReviewWorkflow.Result independentReview) {
        public Result {
            chronology = List.copyOf(chronology);
            delegations = List.copyOf(delegations);
            humanDecisions = Map.copyOf(humanDecisions);
            Map<String, Object> persisted = new java.util.LinkedHashMap<>();
            persisted.put("taskId", taskId); persisted.put("attemptId", attemptId);
            persisted.put("sourceCommit", sourceCommit); persisted.put("status", status);
            persisted.put("chronology", chronology); persisted.put("delegations", delegations);
            persisted.put("humanDecisions", humanDecisions);
            if (approvedManifestId != null) persisted.put("approvedManifestId", approvedManifestId);
            if (approvedBy != null) persisted.put("approvedBy", approvedBy);
            if (cancellationReasonDigest != null) persisted.put("cancellationReasonDigest", cancellationReasonDigest);
            if (independentReview != null) persisted.put("independentReview", independentReview);
            TemporalPayloadGuard.requireSafePayload(persisted);
        }

        public Result(String taskId, String attemptId, String sourceCommit, String status,
                      List<String> chronology, List<DelegationWorkflow.Result> delegations,
                      Map<String, String> humanDecisions, String approvedManifestId,
                      String approvedBy, String cancellationReasonDigest) {
            this(taskId, attemptId, sourceCommit, status, chronology, delegations, humanDecisions,
                    approvedManifestId, approvedBy, cancellationReasonDigest, null);
        }
    }

    record ApprovalRequest(String manifestId, String uri, String digest) {}

    record ApprovalSignal(String taskId, String attemptId, String manifestId, String manifestDigest,
                          String decision, String approver, String decidedAt) {}

    record HumanDecisionRequest(String decisionId, String questionDigest, Set<String> allowedDecisions,
                                List<String> evidenceUris, String objectDigest,
                                Set<String> requiredApproverRoles) {
        public HumanDecisionRequest {
            questionDigest = digestText(questionDigest);
            allowedDecisions = Set.copyOf(allowedDecisions);
            evidenceUris = List.copyOf(evidenceUris);
            requiredApproverRoles = requiredApproverRoles == null ? Set.of() : Set.copyOf(requiredApproverRoles);
        }

        public HumanDecisionRequest(String decisionId, String question, Set<String> allowedDecisions,
                                    List<String> evidenceUris) {
            this(decisionId, question, allowedDecisions, evidenceUris, null, Set.of());
        }
    }

    record HumanDecisionSignal(String taskId, String attemptId, String decisionId, String decision,
                               String objectDigest, String actor, String actorRole, String decidedAt) {
        public HumanDecisionSignal(String taskId, String attemptId, String decisionId, String decision,
                                   String actor, String decidedAt) {
            this(taskId, attemptId, decisionId, decision, null, actor, null, decidedAt);
        }
    }

    record CancellationSignal(String taskId, String attemptId, String reasonDigest, String actor, String decidedAt) {
        public CancellationSignal {
            reasonDigest = digestText(reasonDigest);
        }
    }

    record DelegationView(String nodeId, String parentNodeId, String role, String status) {}

    record PendingEffectView(String type, String id) {}

    private static String digestText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Workflow text is required");
        return value.matches("[0-9a-f]{64}") ? value : TemporalIds.sha256(value);
    }
}
