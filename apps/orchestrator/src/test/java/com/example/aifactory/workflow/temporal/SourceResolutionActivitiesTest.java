package com.example.aifactory.workflow.temporal;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.service.ProcessRunner;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SourceResolutionActivitiesTest {
    @TempDir Path root;

    @Test
    void onlyBusinessRejectionsBecomeGateResults() {
        assertThat(SoftwareFactoryExecutionWorkflowV1Impl.isBusinessGateFailure(
                io.temporal.failure.ApplicationFailure.newNonRetryableFailure("denied", "BUSINESS_REJECTION")))
                .isTrue();
        assertThat(SoftwareFactoryExecutionWorkflowV1Impl.isBusinessGateFailure(
                io.temporal.failure.ApplicationFailure.newFailure("offline", "DEPENDENCY_UNAVAILABLE")))
                .isFalse();
    }

    @Test
    void pipelineModeCannotLaunchHierarchicalChildren() {
        var delegation = new DelegationWorkflow.Request("task-1", "attempt-1", "node-1", null,
                "developer", "a".repeat(40), "implement", java.util.Set.of(),
                new DelegationWorkflow.Budget(10, 1_000, 2));
        var request = new SoftwareFactoryWorkflow.Request("task-1", "attempt-1", "acme/repo",
                "UNRESOLVED", "change", List.of(delegation), null, List.of(), null, null, null,
                new SoftwareFactoryWorkflow.SourceLocation(
                        "http://gitea:3000/acme/repo.git", "main", "ai-factory-context"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> SoftwareFactoryExecutionWorkflowV1Impl.requireProductionExecutionMode(request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reusesTheCommitFromAnExistingIdempotentWorkspace() throws Exception {
        ProcessRunner runner = mock(ProcessRunner.class);
        AiFactoryProperties properties = mock(AiFactoryProperties.class);
        when(properties.workspaceRoot()).thenReturn(root.toString());
        String url = "http://gitea:3000/acme/customer-api.git";
        String commit = "a".repeat(40);
        when(runner.run(anyList(), nullable(Path.class), any())).thenAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            if (command.size() > 1 && "clone".equals(command.get(1))) {
                Files.createDirectories(Path.of(command.getLast()));
                return "";
            }
            if (command.contains("get-url")) return url + "\n";
            if (command.contains("rev-parse")) return commit + "\n";
            throw new AssertionError("Unexpected command " + command);
        });
        SourceResolutionActivitiesImpl activities = new SourceResolutionActivitiesImpl(runner, properties);
        SourceResolutionActivities.Request request = new SourceResolutionActivities.Request(
                "task-1", "attempt-1", "acme/customer-api", url, "main", "effect-" + "b".repeat(64));

        SourceResolutionActivities.Result first = activities.resolve(request);
        SourceResolutionActivities.Result replay = activities.resolve(request);

        assertThat(first).isEqualTo(replay);
        assertThat(first.sourceCommit()).isEqualTo(commit);
        assertThat(first.attestationDigest()).matches("[0-9a-f]{64}");
        verify(runner, times(1)).run(org.mockito.ArgumentMatchers.argThat(command -> command.contains("clone")),
                nullable(Path.class), any());
    }

    @Test
    void productionWorkflowFreezesTheResolvedCommitBeforeCoordination() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker workflowWorker = environment.newWorker("ai-factory-workflows");
            workflowWorker.registerWorkflowImplementationTypes(SoftwareFactoryExecutionWorkflowV1Impl.class);
            Worker contextWorker = environment.newWorker("ai-factory-context");
            CompactPipelineActivities pipeline = new CompactPipelineActivities();
            contextWorker.registerActivitiesImplementations((SourceResolutionActivities) request ->
                    new SourceResolutionActivities.Result(request.repositoryId(), request.branch(), "c".repeat(40),
                            "/workspace/" + request.taskId(), "d".repeat(64)), pipeline);
            environment.newWorker("ai-factory-llm").registerActivitiesImplementations(pipeline);
            environment.newWorker("ai-factory-sandbox").registerActivitiesImplementations(pipeline);
            environment.newWorker("ai-factory-assurance").registerActivitiesImplementations(pipeline);
            environment.newWorker("ai-factory-evidence").registerActivitiesImplementations(pipeline);
            environment.newWorker("ai-factory-scm").registerActivitiesImplementations(pipeline);
            environment.start();
            SoftwareFactoryExecutionWorkflowV1 workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryExecutionWorkflowV1.class, WorkflowOptions.newBuilder()
                            .setTaskQueue("ai-factory-workflows").setWorkflowId("source-resolution-test").build());
            SoftwareFactoryWorkflow.Request request = new SoftwareFactoryWorkflow.Request(
                    "task-1", "attempt-1", "acme/customer-api", "UNRESOLVED", "change", List.of(), null,
                    List.of(), null, null, null,
                    new SoftwareFactoryWorkflow.SourceLocation(
                            "http://gitea:3000/acme/customer-api.git", "main", "ai-factory-context"));

            SoftwareFactoryWorkflow.Result result = workflow.run(request);

            assertThat(result.sourceCommit()).isEqualTo("c".repeat(40));
            assertThat(result.status()).isEqualTo("WAITING_APPROVAL");
            assertThat(result.chronology()).contains("STEP_COMPLETED:plan", "STEP_COMPLETED:review");
            assertThat(pipeline.repairs).isEqualTo(1);
        }
    }

    private static final class CompactPipelineActivities implements PipelineExecutionActivities {
        private int validations;
        private int repairs;

        @Override
        public com.example.aifactory.service.PipelineStepContracts.Result bindSource(SourceBinding binding) {
            return result("bind-source", binding.taskId(), binding.attemptId(), binding.sourceCommit(), Map.of());
        }

        @Override
        public com.example.aifactory.service.PipelineStepContracts.Result execute(StepRequest request) {
            String step = request.command().step();
            Map<String, com.example.aifactory.service.PipelineStepContracts.ArtifactReference> artifacts =
                    "apply-patch".equals(step) ? Map.of() : Map.of(artifactName(step),
                    new com.example.aifactory.service.PipelineStepContracts.ArtifactReference(
                            "evidence://task-1/attempt-1/" + artifactName(step), "e".repeat(64),
                            1, "COMPLETE", "PASSED"));
            return com.example.aifactory.service.PipelineStepContracts.Result.from(
                    request.command(), request.command().sourceCommit(), artifacts);
        }

        @Override
        public com.example.aifactory.service.PipelineStepContracts.Result generatePatchCandidate(StepRequest request) {
            return com.example.aifactory.service.PipelineStepContracts.Result.from(request.command(),
                    request.command().sourceCommit(), Map.of("patch-candidate", artifact("patch-candidate", "GENERATED")));
        }

        @Override
        public PatchValidationResult validatePatchCandidate(StepRequest request) {
            validations++;
            if (validations == 1) {
                var error = artifact("patch-validation-error", "INVALID");
                return new PatchValidationResult(false,
                        com.example.aifactory.service.PipelineStepContracts.Result.from(request.command(),
                                request.command().sourceCommit(), Map.of("patch-validation-error", error)), error);
            }
            return new PatchValidationResult(true,
                    com.example.aifactory.service.PipelineStepContracts.Result.from(request.command(),
                            request.command().sourceCommit(), Map.of("patch", artifact("patch", "VALID"))), null);
        }

        @Override
        public com.example.aifactory.service.PipelineStepContracts.Result repairPatchCandidate(
                PatchRepairRequest request) {
            repairs++;
            return com.example.aifactory.service.PipelineStepContracts.Result.from(request.command(),
                    request.command().sourceCommit(), Map.of("patch-candidate", artifact("patch-candidate", "REPAIRED")));
        }

        @Override
        public com.example.aifactory.model.PendingEffect prepareDelivery(DeliveryRequest request) {
            return new com.example.aifactory.model.PendingEffect("scm.create_draft_pull_request", Map.of(),
                    "draft PR", "ALLOW", true);
        }

        @Override public void recordGateRejection(GateRejection rejection) { }

        private static String artifactName(String step) {
            return switch (step) {
                case "generate-patch" -> "patch";
                case "test" -> "tests";
                default -> step;
            };
        }

        private static com.example.aifactory.service.PipelineStepContracts.ArtifactReference artifact(
                String name, String verdict) {
            return new com.example.aifactory.service.PipelineStepContracts.ArtifactReference(
                    "evidence://task-1/attempt-1/" + name, "e".repeat(64), 1, "COMPLETE", verdict);
        }

        private static com.example.aifactory.service.PipelineStepContracts.Result result(
                String step, String taskId, String attemptId, String sourceCommit,
                Map<String, com.example.aifactory.service.PipelineStepContracts.ArtifactReference> artifacts) {
            var command = new com.example.aifactory.service.PipelineStepContracts.Command(
                    com.example.aifactory.service.PipelineStepContracts.SCHEMA_VERSION, step, taskId, attemptId,
                    TemporalIds.workflow(taskId, attemptId), "acme/customer-api", sourceCommit,
                    Map.of("input", "f".repeat(64)));
            return com.example.aifactory.service.PipelineStepContracts.Result.from(command, sourceCommit, artifacts);
        }
    }
}
