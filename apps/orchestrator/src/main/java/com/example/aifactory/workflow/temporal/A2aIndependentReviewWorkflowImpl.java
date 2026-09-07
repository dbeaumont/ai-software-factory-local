package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aExecutionContext;
import com.example.aifactory.a2a.A2aEvidencePartFactory;
import com.example.aifactory.a2a.A2aEnvelopeFactory;
import com.example.aifactory.a2a.A2aExtensions;
import com.example.aifactory.a2a.A2aMediaTypes;
import com.example.aifactory.service.IndependentReviewBundle;
import io.temporal.common.VersioningBehavior;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowVersioningBehavior;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Dedicated A2A review boundary containing only validated Evidence references and their digests. */
public final class A2aIndependentReviewWorkflowImpl implements IndependentReviewWorkflow {
    private static final String ROLE = "independent-reviewer";
    private static final String SKILL = "independent-reviewer.integration-result-v1";
    private final A2aActivities.Stubs activities = A2aActivities.newStubs();
    private final A2aTaskAwaiter tasks = new A2aTaskAwaiter();

    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public Result run(Request request) {
        requireRequest(request);
        A2aContracts.AgentCardDescriptor card = activities.resolveAgent().resolveAgent(ROLE);
        if (!card.skillIds().contains(SKILL)) {
            throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                    "Independent reviewer card lacks required skill", "INCOMPATIBLE_SCHEMA");
        }
        List<A2aContracts.Part> references = references(request.bundle());
        List<String> digests = references.stream().map(part -> String.valueOf(part.data().get("digest")))
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new), List::copyOf));
        var info = Workflow.getInfo();
        String delegationId = TemporalIds.delegation(request.taskId(), request.attemptId(), request.reviewId());
        A2aExecutionContext execution = new A2aExecutionContext("1", request.taskId(), request.attemptId(),
                info.getWorkflowId(), info.getRunId(), request.taskId(), request.sourceCommit(), delegationId,
                null, ROLE, digests);
        String messageId = TemporalIds.sha256(String.join("\n", request.reviewId(), card.cardDigest(),
                String.join("\n", digests)));
        Map<String, Object> metadata = Map.of(A2aExtensions.EXECUTION_CONTEXT_V1, executionMetadata(execution),
                "reviewBundleDigest", TemporalIds.sha256(String.join("\n", digests)));
        A2aContracts.SendCommand command = new A2aContracts.SendCommand(ROLE, SKILL, messageId, null, null,
                List.of(A2aEnvelopeFactory.create(ROLE, SKILL, "independent-review-v1", references,
                        request.budget())), metadata, true);
        A2aContracts.TaskSnapshot submitted = activities.reconcileDispatch().reconcileDispatch(
                new A2aActivities.DispatchRequest(execution, card.cardDigest(), command));
        A2aContracts.Notification terminal = tasks.awaitUntilTerminal(ROLE, submitted, Duration.ofSeconds(30),
                activities.getTask(), () -> A2aWorkflowHistoryGuard.independentReview(request));
        if (terminal.state() != A2aContracts.TaskState.COMPLETED) {
            return new Result(request.reviewId(), ROLE,
                    terminal.state() == A2aContracts.TaskState.CANCELED ? "CANCELLED" : "FAILED");
        }
        A2aContracts.TaskSnapshot completed = new A2aContracts.TaskSnapshot(terminal.taskId(),
                terminal.contextId(), terminal.state(), terminal.occurredAt(), terminal.artifacts(),
                Map.of("sequence", terminal.sequence()));
        activities.validateArtifacts().validateArtifacts(new A2aActivities.ValidationRequest(
                ROLE, "independent-review-v1", request.taskId(), request.attemptId(), completed));
        return new Result(request.reviewId(), ROLE, "READY_FOR_ACTIVITIES");
    }

    @Override
    public void a2aTaskUpdate(A2aContracts.Notification notification) { tasks.accept(notification); }

    private static List<A2aContracts.Part> references(IndependentReviewBundle bundle) {
        List<A2aContracts.Part> parts = new ArrayList<>();
        parts.add(reference(bundle.consolidatedPatch().patchId(), bundle.consolidatedPatch().uri(),
                bundle.consolidatedPatch().digest(), "integration-result-v1"));
        parts.add(reference(bundle.finalManifest().manifestId(), bundle.finalManifest().uri(),
                bundle.finalManifest().digest(), "integration-result-v1"));
        bundle.reviewedResults().forEach(result -> parts.add(reference(result.resultId(), result.uri(),
                result.digest(), "specialist-result-v1")));
        bundle.contradictions().forEach(contradiction -> parts.add(reference(contradiction.contradictionId(),
                contradiction.uri(), contradiction.digest(), "contradiction-v1")));
        return List.copyOf(parts);
    }

    private static A2aContracts.Part reference(String id, String uri, String digest, String contract) {
        return A2aEvidencePartFactory.reference(id, uri, digest, contract);
    }

    private static void requireRequest(Request request) {
        if (request == null || request.taskId() == null || request.attemptId() == null
                || request.reviewId() == null || !request.reviewId().matches("[A-Za-z0-9_-]{1,128}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.bundle() == null || !request.bundle().boundTo(
                request.taskId(), request.attemptId(), request.sourceCommit())) {
            throw new IllegalArgumentException("Independent review workflow request is invalid");
        }
    }

    private static Map<String, Object> executionMetadata(A2aExecutionContext value) {
        return Map.of("schemaVersion", value.schemaVersion(), "taskId", value.taskId(),
                "attemptId", value.attemptId(), "workflowId", value.workflowId(),
                "workflowRunId", value.workflowRunId(), "repositoryId", value.repositoryId(),
                "sourceCommit", value.sourceCommit(), "delegationId", value.delegationId(),
                "agentRole", value.agentRole(), "inputDigests", value.inputDigests());
    }
}
