package com.example.aifactory.workflow.temporal;

import com.example.aifactory.model.PendingEffect;
import com.example.aifactory.service.PipelineStepContracts;
import com.example.aifactory.workflow.EvidenceRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SoftwareFactoryExecutionWorkflowV1Test {
    private static final String COMMIT = "a".repeat(40);
    private static final String MANIFEST_ID = "b".repeat(64);
    private static final String MANIFEST_DIGEST = "c".repeat(64);

    @Test
    void runsTheProductionPipelineThroughApprovalAndExactlyOneDelivery() {
        AtomicInteger deliveries = new AtomicInteger();
        TestActivities activities = new TestActivities(deliveries);
        try (TestWorkflowEnvironment environment = environment(activities)) {
            SoftwareFactoryExecutionWorkflowV1 workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryExecutionWorkflowV1.class, WorkflowOptions.newBuilder()
                            .setWorkflowId("ai-factory/task-1/pipeline-1")
                            .setTaskQueue("test-workflow").build());
            SoftwareFactoryWorkflow.Request request = request();

            WorkflowClient.start(workflow::run, request);
            awaitStatus(workflow, "WAITING_APPROVAL");
            workflow.approve(new SoftwareFactoryWorkflow.ApprovalSignal(
                    "task-1", "pipeline-1", MANIFEST_ID, MANIFEST_DIGEST,
                    "APPROVE", "operator@example.test", "2026-09-06T00:00:00Z"));
            SoftwareFactoryWorkflow.Result result = WorkflowStub.fromTyped(workflow)
                    .getResult(SoftwareFactoryWorkflow.Result.class);

            assertThat(result.status()).isEqualTo("PR_CREATED");
            assertThat(result.approvedManifestId()).isEqualTo(MANIFEST_ID);
            assertThat(result.chronology()).contains("DELIVERY_COMPLETED");
            assertThat(deliveries).hasValue(1);
            assertThat(workflow.evidence()).hasSizeGreaterThanOrEqualTo(7)
                    .allMatch(uri -> uri.startsWith("evidence://task-1/pipeline-1/"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"test", "quality", "security", "review"})
    void rejectsEveryBusinessGateAndPreservesPreviouslyProducedEvidence(String rejectedGate) {
        AtomicInteger deliveries = new AtomicInteger();
        TestActivities activities = new TestActivities(deliveries, rejectedGate);
        try (TestWorkflowEnvironment environment = environment(activities)) {
            SoftwareFactoryExecutionWorkflowV1 workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryExecutionWorkflowV1.class, WorkflowOptions.newBuilder()
                            .setWorkflowId("ai-factory/task-1/pipeline-1-" + rejectedGate)
                            .setTaskQueue("test-workflow").build());

            SoftwareFactoryWorkflow.Result result = workflow.run(request());

            assertThat(result.status()).isEqualTo("GATE_REJECTED:" + rejectedGate);
            assertThat(result.chronology()).contains("GATE_REJECTED:" + rejectedGate)
                    .anyMatch(event -> event.startsWith("EVIDENCE_PRESERVED:"));
            assertThat(activities.rejectedGates).containsExactly(rejectedGate);
            assertThat(workflow.evidence()).isNotEmpty();
            assertThat(deliveries).hasValue(0);
        }
    }

    private static TestWorkflowEnvironment environment(TestActivities activities) {
        TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance();
        Map<String, Worker> workers = new LinkedHashMap<>();
        for (String queue : queues().values()) workers.put(queue, environment.newWorker(queue));
        workers.get("test-workflow").registerWorkflowImplementationTypes(
                SoftwareFactoryExecutionWorkflowV1Impl.class);
        workers.get("test-context").registerActivitiesImplementations(activities);
        for (String queue : List.of("test-llm", "test-sandbox", "test-assurance",
                "test-evidence", "test-scm")) {
            workers.get(queue).registerActivitiesImplementations(activities);
        }
        environment.start();
        return environment;
    }

    private static SoftwareFactoryWorkflow.Request request() {
        return new SoftwareFactoryWorkflow.Request(
                "task-1", "pipeline-1", "customer-api", PipelineStepContracts.UNRESOLVED_SOURCE_COMMIT,
                "add endpoint", List.of(), null, List.of(), null, null, null,
                new SoftwareFactoryWorkflow.SourceLocation(
                        "http://gitea:3000/aiadmin/customer-api.git", "main", "test-context", queues()),
                SoftwareFactoryWorkflow.WorkflowExecutionMode.PIPELINE, null);
    }

    private static Map<String, String> queues() {
        return Map.of("workflow", "test-workflow", "context", "test-context", "llm", "test-llm",
                "sandbox", "test-sandbox", "assurance", "test-assurance", "evidence", "test-evidence",
                "scm", "test-scm");
    }

    private static void awaitStatus(SoftwareFactoryExecutionWorkflowV1 workflow, String expected) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (expected.equals(workflow.status())) return;
            Thread.onSpinWait();
        }
        assertThat(workflow.status()).isEqualTo(expected);
    }

    private static final class TestActivities implements SourceResolutionActivities, PipelineExecutionActivities {
        private final AtomicInteger deliveries;
        private final String rejectedGate;
        private final java.util.List<String> rejectedGates = new java.util.concurrent.CopyOnWriteArrayList<>();

        private TestActivities(AtomicInteger deliveries) {
            this(deliveries, null);
        }

        private TestActivities(AtomicInteger deliveries, String rejectedGate) {
            this.deliveries = deliveries;
            this.rejectedGate = rejectedGate;
        }

        @Override public SourceResolutionActivities.Result resolve(SourceResolutionActivities.Request request) {
            return new SourceResolutionActivities.Result(
                    request.repositoryId(), request.branch(), COMMIT, "/workspace/tasks/task-1", "d".repeat(64));
        }

        @Override public PipelineStepContracts.Result bindSource(SourceBinding binding) {
            return result("bind-source", Map.of());
        }

        @Override public PipelineStepContracts.Result execute(StepRequest request) {
            String step = request.command().step();
            if (step.equals(rejectedGate)) {
                throw io.temporal.failure.ApplicationFailure.newNonRetryableFailure(
                        "gate rejected by fixture", "BUSINESS_REJECTION");
            }
            String name = switch (step) {
                case "plan" -> "plan";
                case "apply-patch" -> "integration";
                case "test" -> "tests";
                case "quality" -> "quality";
                case "security" -> "security";
                case "review" -> "review";
                default -> throw new IllegalArgumentException(step);
            };
            return result(step, Map.of(name, artifact(name)));
        }

        @Override public PipelineStepContracts.Result generatePatchCandidate(StepRequest request) {
            return result(request.command().step(), Map.of("patch-candidate", artifact("patch-candidate")));
        }

        @Override public PatchValidationResult validatePatchCandidate(StepRequest request) {
            return new PatchValidationResult(true,
                    result(request.command().step(), Map.of("patch", artifact("patch"))), null);
        }

        @Override public PipelineStepContracts.Result repairPatchCandidate(PatchRepairRequest request) {
            throw new AssertionError("valid candidate must not be repaired");
        }

        @Override public PendingEffect prepareDelivery(DeliveryRequest request) {
            return new PendingEffect("scm.create_draft_pull_request", Map.of("repository", "customer-api"),
                    "Create a draft PR", "ALLOW", true);
        }

        @Override public String deliver(DeliveryRequest request) {
            deliveries.incrementAndGet();
            return "http://localhost:3000/aiadmin/customer-api/pulls/1";
        }

        @Override public void recordGateRejection(GateRejection rejection) { rejectedGates.add(rejection.gate()); }
        @Override public void recordCancellation(Cancellation cancellation) {}
        @Override public void recordApproval(Approval approval) {}
        @Override public void recordHumanDecision(HumanDecision decision) {}

        @Override public EvidenceRepository.StoredManifest createApprovalManifest(ApprovalManifestRequest request) {
            return new EvidenceRepository.StoredManifest(MANIFEST_ID,
                    "evidence://task-1/pipeline-1/manifest/" + MANIFEST_ID, MANIFEST_DIGEST, "COMPLETE",
                    "CONFIDENTIAL", Instant.parse("2027-09-06T00:00:00Z"),
                    Instant.parse("2026-09-06T00:00:00Z"));
        }

        private static PipelineStepContracts.Result result(
                String step, Map<String, PipelineStepContracts.ArtifactReference> artifacts) {
            return new PipelineStepContracts.Result(1, step, "task-1", "pipeline-1", COMMIT, artifacts);
        }

        private static PipelineStepContracts.ArtifactReference artifact(String name) {
            String digest = TemporalIds.sha256(name);
            return new PipelineStepContracts.ArtifactReference(
                    "evidence://task-1/pipeline-1/" + name + '/' + digest,
                    digest, 64, "COMPLETE", "PASS");
        }
    }
}
