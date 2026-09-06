package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aMediaTypes;
import com.example.aifactory.service.IndependentReviewBundle;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class A2aIndependentReviewWorkflowTest {
    @Test
    void exposesOnlyValidatedEvidenceReferencesToTheIndependentReviewer() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("a2a-review-test");
            worker.registerWorkflowImplementationTypes(A2aIndependentReviewWorkflowImpl.class);
            AtomicReference<A2aContracts.SendCommand> sent = new AtomicReference<>();
            worker.registerActivitiesImplementations((A2aActivities.ResolveAgent) role ->
                    new A2aContracts.AgentCardDescriptor(role,
                            URI.create("https://a2a-independent-reviewer/.well-known/agent-card.json"),
                            URI.create("https://a2a-independent-reviewer/a2a"), "JSONRPC", "1.0", "a".repeat(64),
                            List.of("independent-reviewer.integration-result-v1"), false, true));
            worker.registerActivitiesImplementations((A2aActivities.ReconcileDispatch) request -> {
                sent.set(request.command());
                return completed();
            });
            worker.registerActivitiesImplementations((A2aActivities.ValidateArtifacts) request ->
                    new A2aActivities.ValidatedArtifacts(request.task().taskId(), List.of(
                            new A2aActivities.EvidenceReference("review-1",
                                    request.task().artifacts().getFirst().parts().getFirst().uri().toString(),
                                    "e".repeat(64), "independent-review-v1"))));
            environment.start();
            IndependentReviewWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    IndependentReviewWorkflow.class, WorkflowOptions.newBuilder()
                            .setWorkflowId("review/task-1/attempt-1/final-review")
                            .setTaskQueue("a2a-review-test").build());
            IndependentReviewBundle bundle = bundle();

            IndependentReviewWorkflow.Result result = workflow.run(new IndependentReviewWorkflow.Request(
                    "task-1", "attempt-1", "final-review", "0".repeat(40), bundle, null));

            assertThat(result).isEqualTo(new IndependentReviewWorkflow.Result(
                    "final-review", "independent-reviewer", "READY_FOR_ACTIVITIES"));
            assertThat(sent.get().parts()).allSatisfy(part -> {
                assertThat(part.mediaType()).isEqualTo(A2aMediaTypes.EVIDENCE_REFERENCE);
                assertThat(part.text()).isNull();
                assertThat(part.data()).containsKeys("uri", "digest", "contract")
                        .doesNotContainKeys("prompt", "reasoning", "raw_output", "private_output");
            });
            assertThat(sent.get().parts()).extracting(part -> part.data().get("digest"))
                    .containsExactlyInAnyOrder("b".repeat(64), "c".repeat(64), "d".repeat(64), "f".repeat(64));
        }
    }

    private static IndependentReviewBundle bundle() {
        return new IndependentReviewBundle("task-1", "attempt-1", "0".repeat(40),
                new IndependentReviewBundle.ConsolidatedPatch("patch-1", "evidence://task-1/patch",
                        "b".repeat(64), List.of("src/App.java")),
                new IndependentReviewBundle.FinalManifest("c".repeat(64), "evidence://task-1/manifest",
                        "c".repeat(64)),
                List.of(new IndependentReviewBundle.ResultReference("result-1", "developer",
                        "evidence://task-1/result", "d".repeat(64))),
                List.of(new IndependentReviewBundle.ContradictionReference("contradiction-1", "RESOLVED",
                        "evidence://task-1/contradiction", "f".repeat(64))));
    }

    private static A2aContracts.TaskSnapshot completed() {
        String uri = "evidence://task-1/attempt-1/agent-result/review.json";
        A2aContracts.Part part = new A2aContracts.Part(A2aMediaTypes.EVIDENCE_REFERENCE, null,
                Map.of("uri", uri, "digest", "e".repeat(64), "contract", "independent-review-v1"),
                URI.create(uri));
        return new A2aContracts.TaskSnapshot("remote-review-1", "review-context-1",
                A2aContracts.TaskState.COMPLETED, Instant.EPOCH,
                List.of(new A2aContracts.Artifact("review-1", "review", List.of(part), Map.of())),
                Map.of("sequence", 1L));
    }
}
