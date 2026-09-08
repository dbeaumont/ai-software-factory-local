package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.model.TaskRoutingFacts;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SoftwareFactoryExecutionWorkflowV2Test {
    @Test
    void usesTheSharedDurableRuntimeWithoutDependingOnTheV1WorkflowClass() {
        assertThat(SoftwareFactoryExecutionWorkflowV2Impl.class.getSuperclass())
                .isEqualTo(ProductionExecutionWorkflowRuntime.class)
                .isNotEqualTo(SoftwareFactoryExecutionWorkflowV1Impl.class);
    }

    @Test
    void keepsExecutionModeOutOfThePersistedV2Contract() {
        assertThat(Arrays.stream(SoftwareFactoryExecutionWorkflowV2.Request.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("executionMode", "requestedMode", "effectiveMode");
    }

    @Test
    void makesHierarchicalExecutionImplicitAtTheV1CompatibilityBoundary() {
        var request = new SoftwareFactoryExecutionWorkflowV2.Request(
                "task-1", "attempt-1", "customer-api", "UNRESOLVED", "requirement",
                new SoftwareFactoryWorkflow.SourceLocation("http://gitea/repo.git", "main", "context", Map.of()),
                null, TaskRoutingFacts.qualifiedLowRiskFixture());

        assertThat(request.requirementDigest()).matches("[0-9a-f]{64}");
        assertThat(request.hierarchicalRequest().executionMode())
                .isEqualTo(SoftwareFactoryWorkflow.WorkflowExecutionMode.HIERARCHICAL_ACTIVE);
    }

    @Test
    void resolvesTheSourceThenFailsClosedOnThePersistedHumanTriageDecision() throws Exception {
        AtomicInteger resolutions = new AtomicInteger();
        io.temporal.common.WorkflowExecutionHistory history;
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            TriageProjectionActivities pipeline = new TriageProjectionActivities();
            var workflowWorker = environment.newWorker("test-workflow");
            workflowWorker.registerWorkflowImplementationTypes(SoftwareFactoryExecutionWorkflowV2Impl.class);
            var contextWorker = environment.newWorker("test-context");
            contextWorker.registerActivitiesImplementations(new SourceResolutionActivities() {
                @Override public Result resolve(Request request) {
                    resolutions.incrementAndGet();
                    return new Result(request.repositoryId(), request.branch(), "a".repeat(40),
                            "/workspace/task-1", "b".repeat(64));
                }
            }, (HierarchicalRoutingActivities) request -> new HierarchicalRoutingActivities.Decision(
                    "c".repeat(64), "routing-policy-v1", "1", Map.of("risk", "R4"),
                    "human-triage", "HUMAN_TRIAGE", List.of("Risk requires triage"), List.of(),
                    "BEFORE_CODE"), pipeline);
            environment.newWorker("test-evidence").registerActivitiesImplementations(pipeline);
            environment.start();
            SoftwareFactoryExecutionWorkflowV2 workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryExecutionWorkflowV2.class, WorkflowOptions.newBuilder()
                            .setWorkflowId("ai-factory/task-1/attempt-1")
                            .setTaskQueue("test-workflow").build());
            TaskRoutingFacts facts = new TaskRoutingFacts("QUALIFIED", "R4", 1, 1, 1, 1,
                    java.util.Set.of(), false, true, false, true);

            SoftwareFactoryWorkflow.Result result = workflow.run(new SoftwareFactoryExecutionWorkflowV2.Request(
                    "task-1", "attempt-1", "customer-api", "UNRESOLVED", "requirement",
                    new SoftwareFactoryWorkflow.SourceLocation(
                            "http://gitea:3000/aiadmin/customer-api.git", "main", "test-context",
                            Map.of("context", "test-context", "evidence", "test-evidence")), null, facts));

            assertThat(result.status()).isEqualTo("GATE_REJECTED:routing");
            assertThat(result.chronology()).contains("ROUTING_DECIDED:" + "c".repeat(64) + ":HUMAN_TRIAGE");
            assertThat(resolutions).hasValue(1);
            assertThat(pipeline.sourceBindings).hasValue(1);
            assertThat(pipeline.routingRejections).hasValue(1);
            history = environment.getWorkflowClient().fetchHistory("ai-factory/task-1/attempt-1");
        }
        WorkflowReplayer.replayWorkflowExecution(history, SoftwareFactoryExecutionWorkflowV2Impl.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void executesTheShortPathWithSupervisorAndWithoutArchitectureOrSecurityAgents(boolean repairPatch) {
        AtomicInteger deliveries = new AtomicInteger();
        var activities = new SoftwareFactoryExecutionWorkflowV1Test.TestActivities(deliveries, repairPatch ? 1 : 0);
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            var workflowWorker = environment.newWorker("test-workflow");
            workflowWorker.registerWorkflowImplementationTypes(
                    SoftwareFactoryExecutionWorkflowV2Impl.class, A2aDelegationWorkflowImpl.class,
                    A2aIndependentReviewWorkflowImpl.class);
            workflowWorker.registerActivitiesImplementations(activities);
            var contextWorker = environment.newWorker("test-context");
            contextWorker.registerActivitiesImplementations(activities,
                    (HierarchicalRoutingActivities) request -> new HierarchicalRoutingActivities.Decision(
                            "c".repeat(64), "routing-policy-v1", "1", Map.of("risk", "R1"),
                            "short-code-path", "SHORT_CODE_PATH", List.of("Bounded scope"),
                            List.of("supervisor", "developer", "independent-reviewer"), "NONE"));
            var hierarchical = new HierarchicalFixture();
            for (String queue : List.of("test-llm", "test-sandbox", "test-assurance", "test-scm")) {
                environment.newWorker(queue).registerActivitiesImplementations(activities);
            }
            environment.newWorker("test-evidence").registerActivitiesImplementations(activities, hierarchical);
            environment.start();
            SoftwareFactoryExecutionWorkflowV2 workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryExecutionWorkflowV2.class, WorkflowOptions.newBuilder()
                            .setWorkflowId("ai-factory/task-1/pipeline-1-short")
                            .setTaskQueue("test-workflow").build());
            TaskRoutingFacts facts = TaskRoutingFacts.qualifiedLowRiskFixture();
            var request = new SoftwareFactoryExecutionWorkflowV2.Request(
                    "task-1", "pipeline-1", "customer-api", "UNRESOLVED", "requirement",
                    new SoftwareFactoryWorkflow.SourceLocation(
                            "http://gitea:3000/aiadmin/customer-api.git", "main", "test-context",
                            Map.of("workflow", "test-workflow", "context", "test-context", "llm", "test-llm",
                                    "sandbox", "test-sandbox", "assurance", "test-assurance",
                                    "evidence", "test-evidence", "scm", "test-scm")), null, facts);

            WorkflowClient.start(workflow::run, request);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
            while (!"WAITING_APPROVAL".equals(workflow.status()) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            workflow.approve(new SoftwareFactoryWorkflow.ApprovalSignal(
                    "task-1", "pipeline-1", "b".repeat(64), "c".repeat(64),
                    "APPROVE", "operator@example.test", "2026-09-08T00:00:00Z"));
            SoftwareFactoryWorkflow.Result result = WorkflowStub.fromTyped(workflow)
                    .getResult(SoftwareFactoryWorkflow.Result.class);

            assertThat(result.status()).isEqualTo("PR_CREATED");
            assertThat(activities.roles).containsExactlyElementsOf(repairPatch
                    ? List.of("supervisor", "developer", "patch-repair", "independent-reviewer")
                    : List.of("supervisor", "developer", "independent-reviewer"));
            assertThat(activities.roles).doesNotContain("architecture-agent", "security-agent");
            assertThat(hierarchical.preparedRoles).containsExactly("supervisor");
            assertThat(deliveries).hasValue(1);
        }
    }

    @Test
    void executesArchitectureAndCodeAsNativeHierarchicalChildren() {
        AtomicInteger deliveries = new AtomicInteger();
        var activities = new SoftwareFactoryExecutionWorkflowV1Test.TestActivities(deliveries);
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            var workflowWorker = environment.newWorker("test-workflow");
            workflowWorker.registerWorkflowImplementationTypes(
                    SoftwareFactoryExecutionWorkflowV2Impl.class, A2aDelegationWorkflowImpl.class,
                    A2aIndependentReviewWorkflowImpl.class);
            workflowWorker.registerActivitiesImplementations(activities);
            var contextWorker = environment.newWorker("test-context");
            contextWorker.registerActivitiesImplementations(activities,
                    (HierarchicalRoutingActivities) request -> new HierarchicalRoutingActivities.Decision(
                            "c".repeat(64), "routing-policy-v1", "1", Map.of("risk", "R1"),
                            "hierarchical-path", "HIERARCHICAL_PATH", List.of("Cross-module scope"),
                            List.of("supervisor", "architecture-agent", "code-agent", "test-agent",
                                    "security-agent", "independent-reviewer"), "NONE"));
            var hierarchical = new HierarchicalFixture();
            for (String queue : List.of("test-llm", "test-sandbox", "test-assurance", "test-scm")) {
                environment.newWorker(queue).registerActivitiesImplementations(activities);
            }
            environment.newWorker("test-evidence").registerActivitiesImplementations(activities, hierarchical);
            environment.start();
            SoftwareFactoryExecutionWorkflowV2 workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryExecutionWorkflowV2.class, WorkflowOptions.newBuilder()
                            .setWorkflowId("ai-factory/task-1/pipeline-1-hierarchical")
                            .setTaskQueue("test-workflow").build());
            TaskRoutingFacts facts = new TaskRoutingFacts("QUALIFIED", "R1", 2, 2, 4, 1,
                    java.util.Set.of("PUBLIC_API"), false, true, false, true);
            var request = new SoftwareFactoryExecutionWorkflowV2.Request(
                    "task-1", "pipeline-1", "customer-api", "UNRESOLVED", "requirement",
                    new SoftwareFactoryWorkflow.SourceLocation(
                            "http://gitea:3000/aiadmin/customer-api.git", "main", "test-context",
                            Map.of("workflow", "test-workflow", "context", "test-context", "llm", "test-llm",
                                    "sandbox", "test-sandbox", "assurance", "test-assurance",
                                    "evidence", "test-evidence", "scm", "test-scm")), null, facts);

            WorkflowClient.start(workflow::run, request);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
            while (!"WAITING_APPROVAL".equals(workflow.status()) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            workflow.approve(new SoftwareFactoryWorkflow.ApprovalSignal(
                    "task-1", "pipeline-1", "b".repeat(64), "c".repeat(64),
                    "APPROVE", "operator@example.test", "2026-09-08T00:00:00Z"));
            SoftwareFactoryWorkflow.Result result = WorkflowStub.fromTyped(workflow)
                    .getResult(SoftwareFactoryWorkflow.Result.class);

            assertThat(result.status()).isEqualTo("PR_CREATED");
            assertThat(activities.roles).containsExactly(
                    "architecture-agent", "code-agent", "developer", "test-design", "test-agent",
                    "security-agent", "independent-reviewer");
            assertThat(hierarchical.preparedRoles).containsExactly(
                    "architecture-agent", "code-agent", "test-design", "test-agent", "security-agent");
            assertThat(deliveries).hasValue(1);
        }
    }

    private static final class TriageProjectionActivities implements PipelineExecutionActivities {
        private final AtomicInteger sourceBindings = new AtomicInteger();
        private final AtomicInteger routingRejections = new AtomicInteger();

        @Override public com.example.aifactory.service.PipelineStepContracts.Result bindSource(SourceBinding binding) {
            sourceBindings.incrementAndGet();
            return result("bind-source", binding.sourceCommit());
        }
        @Override public void recordGateRejection(GateRejection rejection) {
            routingRejections.incrementAndGet();
        }
        @Override public com.example.aifactory.service.PipelineStepContracts.Result execute(StepRequest request) {
            throw unsupported();
        }
        @Override public PatchValidationResult validatePatchCandidate(StepRequest request) { throw unsupported(); }
        @Override public PipelineAgentInput prepareAgentInput(PipelineAgentInputRequest request) { throw unsupported(); }
        @Override public com.example.aifactory.service.PipelineStepContracts.Result consumeAgentResult(
                PipelineAgentResultRequest request) { throw unsupported(); }
        @Override public com.example.aifactory.model.PendingEffect prepareDelivery(DeliveryRequest request) {
            throw unsupported();
        }
        @Override public String deliver(DeliveryRequest request) { throw unsupported(); }
        @Override public void recordCancellation(Cancellation cancellation) { throw unsupported(); }
        @Override public void recordApproval(Approval approval) { throw unsupported(); }
        @Override public void recordHumanDecision(HumanDecision decision) { throw unsupported(); }
        @Override public com.example.aifactory.workflow.EvidenceRepository.StoredManifest createApprovalManifest(
                ApprovalManifestRequest request) { throw unsupported(); }

        private static com.example.aifactory.service.PipelineStepContracts.Result result(
                String step, String commit) {
            return new com.example.aifactory.service.PipelineStepContracts.Result(
                    1, step, "task-1", "attempt-1", commit, Map.of());
        }
        private static AssertionError unsupported() {
            return new AssertionError("A triage route must not execute pipeline activities");
        }
    }

    private static final class HierarchicalFixture implements HierarchicalExecutionActivities {
        private final java.util.List<String> preparedRoles = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public A2aContracts.Part prepareSpecialistTask(PrepareSpecialistTask request) {
            preparedRoles.add(request.role());
            String digest = TemporalIds.sha256(request.role());
            return com.example.aifactory.a2a.A2aEvidencePartFactory.reference(
                    "specialist-" + request.nodeId(),
                    "evidence://task-1/pipeline-1/specialist-task/" + digest,
                    digest, "specialist-task-v1", 64);
        }

        @Override public AcceptedSpecialistResult acceptSpecialistResult(AcceptSpecialistResult request) {
            String id = switch (request.role()) {
                case "supervisor" -> "short-plan-1";
                case "architecture-agent" -> "assessment-1";
                case "code-agent" -> "integration-proposal-1";
                case "test-design" -> "test-strategy-1";
                case "test-agent" -> "test-assessment-1";
                case "security-agent" -> "security-assessment-1";
                default -> throw new IllegalArgumentException(request.role());
            };
            return new AcceptedSpecialistResult(id,
                    new com.example.aifactory.service.PipelineStepContracts.ArtifactReference(
                            request.reference().uri(), request.reference().digest(), 64, "COMPLETE", "ACCEPTED"));
        }

        @Override public java.util.List<DeveloperTask> prepareDeveloperTasks(PrepareDeveloperTasks request) {
            String digest = TemporalIds.sha256("developer-task");
            var input = com.example.aifactory.a2a.A2aEvidencePartFactory.reference(
                    "code-task-1", "evidence://task-1/pipeline-1/code-task/" + digest,
                    digest, "code-task-v1", 64);
            return java.util.List.of(new DeveloperTask(
                    "developer-1", "code-task-1", java.util.Set.of(),
                    new DelegationWorkflow.Budget(12_000, 12_000_000, 6, 900), input));
        }

        @Override public java.util.List<DeveloperTask> prepareShortDeveloperTasks(
                PrepareShortDeveloperTasks request) {
            String digest = TemporalIds.sha256("short-developer-task");
            var input = com.example.aifactory.a2a.A2aEvidencePartFactory.reference(
                    "code-developer-1", "evidence://task-1/pipeline-1/code-task/" + digest,
                    digest, "code-task-v1", 64);
            return java.util.List.of(new DeveloperTask(
                    "developer-1", "code-developer-1", java.util.Set.of(),
                    new DelegationWorkflow.Budget(10_000, 10_000_000, 6, 600), input));
        }

        @Override public AcceptedDeveloperPatches acceptDeveloperPatches(AcceptDeveloperPatches request) {
            var reference = request.results().getFirst().resultReference();
            var artifact = new com.example.aifactory.service.PipelineStepContracts.ArtifactReference(
                    "evidence://task-1/pipeline-1/code-patch/" + reference.digest(),
                    reference.digest(), 64, "COMPLETE", "GENERATED");
            var proposal = new ReviewedSpecialistResult(
                    "patch-proposal-1", "developer",
                    new com.example.aifactory.service.PipelineStepContracts.ArtifactReference(
                            reference.uri(), reference.digest(), 64, "COMPLETE", "ACCEPTED"));
            return new AcceptedDeveloperPatches(artifact, java.util.List.of(proposal));
        }

        @Override public PatchRepairTask preparePatchRepair(PreparePatchRepair request) {
            String digest = TemporalIds.sha256("patch-repair-task-" + request.repairAttempt());
            var input = com.example.aifactory.a2a.A2aEvidencePartFactory.reference(
                    "repair-" + request.repairAttempt(),
                    "evidence://task-1/pipeline-1/patch-repair-task/" + digest,
                    digest, "patch-repair-task-v1", 64);
            return new PatchRepairTask("patch-repair-" + request.repairAttempt(),
                    "repair-" + request.repairAttempt(), request.budget(), input);
        }

        @Override public AcceptedPatchRepair acceptPatchRepair(AcceptPatchRepair request) {
            var reference = request.resultReference();
            var artifact = new com.example.aifactory.service.PipelineStepContracts.ArtifactReference(
                    "evidence://task-1/pipeline-1/code-patch/" + reference.digest(),
                    reference.digest(), 64, "COMPLETE", "REPAIRED");
            return new AcceptedPatchRepair(artifact, new ReviewedSpecialistResult(
                    "repair-proposal-" + request.task().nodeId(), "patch-repair",
                    new com.example.aifactory.service.PipelineStepContracts.ArtifactReference(
                            reference.uri(), reference.digest(), 64, "COMPLETE", "ACCEPTED")));
        }

        @Override public PreparedIndependentReview prepareIndependentReview(PrepareIndependentReview request) {
            var patch = request.artifacts().get("patch");
            var manifest = new com.example.aifactory.workflow.EvidenceRepository.StoredManifest(
                    "b".repeat(64), "evidence://task-1/pipeline-1/manifest/" + "b".repeat(64),
                    "c".repeat(64), "COMPLETE", "CONFIDENTIAL",
                    java.time.Instant.parse("2027-09-08T00:00:00Z"),
                    java.time.Instant.parse("2026-09-08T00:00:00Z"));
            var results = request.reviewedResults().stream().map(result ->
                    new com.example.aifactory.service.IndependentReviewBundle.ResultReference(
                            result.documentId(), result.role(), result.artifact().uri(), result.artifact().digest()))
                    .toList();
            var digests = new java.util.LinkedHashMap<String, String>();
            for (String name : java.util.List.of("plan", "patch", "tests", "quality", "security")) {
                digests.put(name, request.artifacts().get(name).digest());
            }
            var bundle = new com.example.aifactory.service.IndependentReviewBundle(
                    request.taskId(), request.attemptId(), request.sourceCommit(),
                    new com.example.aifactory.service.IndependentReviewBundle.ConsolidatedPatch(
                            "integrated-patch", patch.uri(), patch.digest(), java.util.List.of("src/Main.java")),
                    new com.example.aifactory.service.IndependentReviewBundle.FinalManifest(
                            manifest.manifestId(), manifest.uri(), manifest.digest()),
                    results, java.util.List.of(), java.util.Map.copyOf(digests));
            return new PreparedIndependentReview(bundle, manifest);
        }
    }
}
