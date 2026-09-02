package com.example.aifactory.workflow.projection;

import com.example.aifactory.workflow.EvidenceRepository;
import com.example.aifactory.workflow.temporal.DelegationWorkflow;
import com.example.aifactory.workflow.temporal.SoftwareFactoryWorkflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rebuilds metadata projections from authoritative Temporal history and verified Evidence MCP summaries. */
public final class ProjectionRebuilder {
    private final ProjectionHistorySource histories;
    private final EvidenceRepository evidenceRepository;
    private final UiProjectionStore projectionStore;

    public ProjectionRebuilder(ProjectionHistorySource histories, EvidenceRepository evidenceRepository,
                               UiProjectionStore projectionStore) {
        this.histories = histories;
        this.evidenceRepository = evidenceRepository;
        this.projectionStore = projectionStore;
    }

    public UiProjectionSnapshot rebuild(String workflowId, String runId) {
        ProjectionHistorySource.History history = histories.read(workflowId, runId);
        requireBoundHistory(workflowId, runId, history);
        SoftwareFactoryWorkflow.Request request = history.request();
        String workflowRunId = history.runId();

        Map<String, String> completed = new LinkedHashMap<>();
        if (history.result() != null) {
            history.result().delegations().forEach(result -> completed.put(result.nodeId(), result.status()));
        }
        List<UiProjectionSnapshot.Delegation> delegations = request.delegations().stream()
                .map(item -> delegation(workflowRunId, request, item,
                        completed.getOrDefault(item.nodeId(), "PENDING")))
                .toList();

        List<EvidenceExpectation> expectations = evidenceExpectations(request);
        List<UiProjectionSnapshot.Evidence> verifiedEvidence = new ArrayList<>();
        for (int index = 0; index < expectations.size(); index++) {
            EvidenceExpectation expected = expectations.get(index);
            EvidenceRepository.EvidenceSummary summary = evidenceRepository.getSummary(
                    request.taskId(), request.attemptId(), expected.uri(), "workflow");
            if (!expected.uri().equals(summary.uri()) || !"COMPLETE".equals(summary.status())
                    || expected.digest() != null && !expected.digest().equals(summary.digest())) {
                throw new SecurityException("evidence metadata diverges from Temporal history");
            }
            String stableId = sha256(summary.uri()).substring(0, 32);
            verifiedEvidence.add(new UiProjectionSnapshot.Evidence(
                    "artifact-" + stableId, "evidence-ref-" + stableId, workflowRunId,
                    request.taskId(), request.attemptId(), request.sourceCommit(), summary.type(), summary.uri(),
                    summary.digest(), summary.status(), summary.classification(), summary.sizeBytes()));
        }

        String status = history.result() == null ? history.terminalStatus() : history.result().status();
        UiProjectionSnapshot snapshot = new UiProjectionSnapshot(
                new UiProjectionSnapshot.Task(request.taskId(), request.repositoryId(), request.attemptId(),
                        request.sourceCommit(), sha256(request.requirement()), status,
                        history.startedAt(), history.completedAt() == null ? history.startedAt() : history.completedAt()),
                new UiProjectionSnapshot.WorkflowRun(workflowRunId, history.workflowId(), history.runId(),
                        request.taskId(), request.attemptId(), request.sourceCommit(), status,
                        history.startedAt(), history.completedAt()),
                delegations, verifiedEvidence);
        projectionStore.replaceAtomically(snapshot);
        return snapshot;
    }

    private static UiProjectionSnapshot.Delegation delegation(
            String workflowRunId, SoftwareFactoryWorkflow.Request root,
            DelegationWorkflow.Request item, String status) {
        if (!root.taskId().equals(item.taskId()) || !root.attemptId().equals(item.attemptId())
                || !root.sourceCommit().equals(item.sourceCommit())) {
            throw new SecurityException("delegation lineage diverges from Temporal root history");
        }
        return new UiProjectionSnapshot.Delegation(item.nodeId(), item.parentNodeId(), workflowRunId,
                root.taskId(), root.attemptId(), root.sourceCommit(), item.role(), status,
                item.budget().maxTokens(), item.budget().maxCostMicros(), item.budget().maxTurns());
    }

    private static List<EvidenceExpectation> evidenceExpectations(SoftwareFactoryWorkflow.Request request) {
        Map<String, EvidenceExpectation> unique = new LinkedHashMap<>();
        request.humanDecisionRequests().forEach(decision -> decision.evidenceUris().forEach(uri ->
                unique.putIfAbsent(uri, new EvidenceExpectation(uri, null))));
        if (request.approvalRequest() != null) {
            SoftwareFactoryWorkflow.ApprovalRequest approval = request.approvalRequest();
            unique.put(approval.uri(), new EvidenceExpectation(approval.uri(), approval.digest()));
        }
        return List.copyOf(unique.values());
    }

    private static void requireBoundHistory(String workflowId, String runId,
                                            ProjectionHistorySource.History history) {
        if (history == null || history.request() == null || history.startedAt() == null
                || !workflowId.equals(history.workflowId()) || !runId.equals(history.runId())) {
            throw new SecurityException("Temporal history is incomplete or belongs to another execution");
        }
        SoftwareFactoryWorkflow.Request request = history.request();
        SoftwareFactoryWorkflow.Result result = history.result();
        if (result != null && (!request.taskId().equals(result.taskId())
                || !request.attemptId().equals(result.attemptId())
                || !request.sourceCommit().equals(result.sourceCommit()))) {
            throw new SecurityException("Temporal result lineage diverges from workflow input");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record EvidenceExpectation(String uri, String digest) {}
}
