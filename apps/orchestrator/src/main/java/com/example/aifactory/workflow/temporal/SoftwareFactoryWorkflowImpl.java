package com.example.aifactory.workflow.temporal;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.ContinueAsNewOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowVersioningBehavior;
import io.temporal.common.InitialVersioningBehavior;
import io.temporal.common.VersioningBehavior;

/** Root durable workflow; subsequent migration steps add child workflows and activities. */
public final class SoftwareFactoryWorkflowImpl implements SoftwareFactoryWorkflow {
    private final DelegationScheduler scheduler = new DelegationScheduler();
    private Request request;
    private ApprovalSignal receivedApproval;
    private CancellationSignal receivedCancellation;
    private final Map<String, HumanDecisionSignal> receivedDecisions = new LinkedHashMap<>();
    private final Map<String, DelegationWorkflow.Result> completedDelegations = new LinkedHashMap<>();
    private IndependentReviewWorkflow.Result completedReview;
    private String phase = "CREATED";

    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public Result run(Request request) {
        requireValid(request);
        this.request = request;
        restoreContinuationState(request.continuationState());
        phase = "RUNNING";
        List<String> chronology = new ArrayList<>(request.continuationState().chronology());
        if (chronology.isEmpty()) chronology.add("WORKFLOW_STARTED");
        List<DelegationWorkflow.Result> results = new ArrayList<>(request.continuationState().delegations());
        List<DelegationWorkflow.Request> orderedDelegations = scheduler.validateAndOrder(request, request.delegations());
        int processedThisRun = 0;
        int nextIndex = request.continuationState().nextDelegationIndex();
        while (nextIndex < orderedDelegations.size()) {
            List<DelegationWorkflow.Result> propagated = scheduler.propagateBlocked(
                    orderedDelegations, completedDelegations);
            for (DelegationWorkflow.Result blocked : propagated) {
                results.add(blocked);
                completedDelegations.put(blocked.nodeId(), blocked);
                chronology.add("DELEGATION_BLOCKED:" + blocked.nodeId() + ':' + blocked.status());
            }
            nextIndex += propagated.size();
            if (nextIndex >= orderedDelegations.size()) break;
            int remainingRunCapacity = request.executionPolicy().maxDelegationsPerRun() - processedThisRun;
            if (remainingRunCapacity < 1) {
                continueAsNew(request, nextIndex, results, chronology);
                return null;
            }
            List<DelegationWorkflow.Request> batch = scheduler.ready(
                    orderedDelegations, completedDelegations.keySet(), remainingRunCapacity);
            batch.forEach(delegation -> scheduler.requireDependenciesSatisfied(
                    delegation, completedDelegations.keySet(), orderedDelegations));
            List<DelegationWorkflow.Result> batchResults = scheduler.executeBatch(request, batch);
            for (DelegationWorkflow.Result result : batchResults) {
                results.add(result);
                completedDelegations.put(result.nodeId(), result);
                chronology.add(scheduler.blocksDependents(result)
                        ? "DELEGATION_FAILED:" + result.nodeId() + ':' + result.status()
                        : "DELEGATION_COMPLETED:" + result.nodeId());
            }
            processedThisRun += batch.size();
            nextIndex += batch.size();
            if (nextIndex < orderedDelegations.size()
                    && shouldContinueAsNew(request.executionPolicy(), processedThisRun)) {
                continueAsNew(request, nextIndex, results, chronology);
                return null;
            }
        }
        if (request.independentReview() != null) {
            IndependentReviewWorkflow.Request reviewRequest = request.independentReview();
            IndependentReviewWorkflow reviewer = Workflow.newChildWorkflowStub(IndependentReviewWorkflow.class,
                    ChildWorkflowOptions.newBuilder().setWorkflowId(
                            TemporalIds.delegation(request.taskId(), request.attemptId(), reviewRequest.reviewId()))
                            .build());
            completedReview = reviewer.run(reviewRequest);
            chronology.add("INDEPENDENT_REVIEW_COMPLETED:" + completedReview.reviewId());
        }
        Map<String, String> decisions = new LinkedHashMap<>();
        for (HumanDecisionRequest decision : request.humanDecisionRequests()) {
            requireDecision(decision);
            chronology.add("WAITING_DECISION:" + decision.decisionId());
            phase = "WAITING_HUMAN_DECISION";
            Workflow.await(() -> cancelled() || decisionMatches(decision));
            if (cancelled()) return cancelledResult(request, chronology, results, decisions);
            HumanDecisionSignal received = receivedDecisions.get(decision.decisionId());
            decisions.put(decision.decisionId(), received.decision());
            chronology.add("DECISION_RECORDED:" + decision.decisionId() + ':' + received.decision());
        }
        if (request.approvalRequest() == null) {
            phase = decisions.isEmpty() ? (completedReview != null ? "INDEPENDENT_REVIEW_COMPLETED"
                    : results.isEmpty() ? "READY_FOR_DELEGATION"
                    : results.stream().anyMatch(scheduler::blocksDependents)
                    ? "DELEGATIONS_BLOCKED" : "DELEGATIONS_COMPLETED")
                    : "DECISIONS_COMPLETED";
            return new Result(request.taskId(), request.attemptId(), request.sourceCommit(),
                    phase,
                    chronology, results, decisions, null, null, null, completedReview);
        }
        requireManifest(request.approvalRequest());
        chronology.add("WAITING_APPROVAL:" + request.approvalRequest().manifestId());
        phase = "WAITING_APPROVAL";
        Workflow.await(() -> cancelled() || approvalMatches(receivedApproval));
        if (cancelled()) return cancelledResult(request, chronology, results, decisions);
        chronology.add("APPROVED:" + request.approvalRequest().manifestId());
        phase = "APPROVED";
        return new Result(request.taskId(), request.attemptId(), request.sourceCommit(), "APPROVED",
                chronology, results, decisions, request.approvalRequest().manifestId(), receivedApproval.approver(),
                null, completedReview);
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

    @Override
    public String status() {
        return phase;
    }

    @Override
    public List<DelegationView> dag() {
        if (request == null) return List.of();
        List<DelegationView> result = new ArrayList<>(request.delegations().stream().map(node -> new DelegationView(
                node.nodeId(), node.parentNodeId(), node.role(), completedDelegations.containsKey(node.nodeId())
                ? completedDelegations.get(node.nodeId()).status() : "PENDING")).toList());
        if (request.independentReview() != null) {
            result.add(new DelegationView(request.independentReview().reviewId(), "workflow",
                    "independent-reviewer", completedReview == null ? "PENDING" : completedReview.status()));
        }
        return List.copyOf(result);
    }

    @Override
    public Map<String, DelegationWorkflow.Budget> budgets() {
        if (request == null) return Map.of();
        Map<String, DelegationWorkflow.Budget> result = new LinkedHashMap<>();
        request.delegations().forEach(node -> result.put(node.nodeId(), node.budget()));
        if (request.independentReview() != null) {
            result.put(request.independentReview().reviewId(), request.independentReview().budget());
        }
        return result;
    }

    @Override
    public List<String> evidence() {
        if (request == null) return List.of();
        List<String> result = new ArrayList<>();
        request.humanDecisionRequests().forEach(decision -> result.addAll(decision.evidenceUris()));
        if (request.approvalRequest() != null) result.add(request.approvalRequest().uri());
        return result.stream().distinct().sorted().toList();
    }

    @Override
    public List<PendingEffectView> pendingEffects() {
        if (request == null) return List.of();
        List<PendingEffectView> result = new ArrayList<>();
        request.humanDecisionRequests().stream().filter(decision -> !decisionMatches(decision))
                .forEach(decision -> result.add(new PendingEffectView("HUMAN_DECISION", decision.decisionId())));
        if (request.approvalRequest() != null && !approvalMatches(receivedApproval)) {
            result.add(new PendingEffectView("APPROVAL", request.approvalRequest().manifestId()));
        }
        return List.copyOf(result);
    }

    private static void requireValid(Request request) {
        if (request == null || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.repositoryId() == null || !request.repositoryId().matches("[A-Za-z0-9._/-]{1,63}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.requirement() == null || request.requirement().isBlank()
                || request.continuationState().nextDelegationIndex() < 0
                || request.continuationState().nextDelegationIndex() > request.delegations().size()
                || request.continuationState().generation() < 0
                || request.continuationState().delegations().size()
                != request.continuationState().nextDelegationIndex()
                || request.delegations().stream().anyMatch(delegation ->
                "independent-reviewer".equals(delegation.role()))
                || !reviewMatchesRoot(request)) {
            throw new IllegalArgumentException("Software factory workflow request is invalid");
        }
    }

    private static boolean reviewMatchesRoot(Request request) {
        IndependentReviewWorkflow.Request review = request.independentReview();
        return review == null || request.taskId().equals(review.taskId())
                && request.attemptId().equals(review.attemptId())
                && request.sourceCommit().equals(review.sourceCommit())
                && review.bundle() != null && review.bundle().boundTo(
                request.taskId(), request.attemptId(), request.sourceCommit())
                && request.delegations().stream().noneMatch(delegation ->
                review.reviewId().equals(delegation.nodeId()));
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
                && (expected.objectDigest() == null || expected.objectDigest().equals(signal.objectDigest()))
                && (expected.requiredApproverRoles().isEmpty()
                || expected.requiredApproverRoles().contains(signal.actorRole()))
                && signal.actor() != null && !signal.actor().isBlank()
                && signal.decidedAt() != null && !signal.decidedAt().isBlank();
    }

    private static void requireDecision(HumanDecisionRequest decision) {
        if (decision == null || decision.decisionId() == null
                || !decision.decisionId().matches("[A-Za-z0-9_-]{1,128}")
                || decision.question() == null || decision.question().isBlank()
                || decision.allowedDecisions().isEmpty()
                || decision.objectDigest() != null && !decision.objectDigest().matches("[0-9a-f]{64}")
                || decision.requiredApproverRoles().stream().anyMatch(role -> !Set.of(
                "PRODUCT", "ARCHITECTURE", "SECURITY", "DATA", "PLATFORM", "OPERATIONS").contains(role))) {
            throw new IllegalArgumentException("Human decision request is invalid");
        }
    }

    private Result cancelledResult(Request request, List<String> chronology,
                                   List<DelegationWorkflow.Result> results, Map<String, String> decisions) {
        chronology.add("CANCELLED");
        phase = "CANCELLED";
        return new Result(request.taskId(), request.attemptId(), request.sourceCommit(), "CANCELLED",
                chronology, results, decisions, null, null, receivedCancellation.reason(), completedReview);
    }

    private void restoreContinuationState(ContinuationState state) {
        receivedApproval = state.receivedApproval();
        receivedCancellation = state.receivedCancellation();
        receivedDecisions.clear();
        receivedDecisions.putAll(state.receivedDecisions());
        completedDelegations.clear();
        state.delegations().forEach(result -> completedDelegations.put(result.nodeId(), result));
    }

    private static boolean shouldContinueAsNew(ExecutionPolicy policy, int processedThisRun) {
        return processedThisRun >= policy.maxDelegationsPerRun()
                || Workflow.getInfo().getHistoryLength() >= policy.maxHistoryEvents()
                || Workflow.getInfo().getHistorySize() >= policy.maxHistoryBytes()
                || Workflow.getInfo().isContinueAsNewSuggested();
    }

    private void continueAsNew(Request request, int nextIndex, List<DelegationWorkflow.Result> results,
                               List<String> chronology) {
        int nextGeneration = request.continuationState().generation() + 1;
        chronology.add("CONTINUED_AS_NEW:" + nextGeneration);
        phase = "CONTINUING_AS_NEW";
        ContinuationState state = new ContinuationState(nextIndex, nextGeneration, results, chronology,
                receivedDecisions, receivedApproval, receivedCancellation);
        ContinueAsNewOptions.Builder options = ContinueAsNewOptions.newBuilder();
        if (Workflow.getInfo().isTargetWorkerDeploymentVersionChanged()) {
            options.setInitialVersioningBehavior(InitialVersioningBehavior.AUTO_UPGRADE);
        }
        Workflow.continueAsNew(options.build(), request.continuedWith(state));
    }
}
