package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aMediaTypes;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class A2aDelegationWorkflowTest {
    @ParameterizedTest
    @CsvSource({
            "developer, developer.code-task-v1, patch-proposal-v1",
            "patch-repair, patch-repair.patch-repair-task-v1, patch-repair-proposal-v1",
            "supervisor, supervisor.specialist-task-v1, delegation-plan-v1"
    })
    void executesSpecialistOnlyThroughA2aActivitiesAndValidatesItsArtifact(
            String role, String expectedSkill, String expectedOutputContract) {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("a2a-delegation-test");
            worker.registerWorkflowImplementationTypes(A2aDelegationWorkflowImpl.class);
            AtomicReference<A2aActivities.DispatchRequest> dispatch = new AtomicReference<>();
            worker.registerActivitiesImplementations((A2aActivities.ResolveAgent) requestedRole ->
                    new A2aContracts.AgentCardDescriptor(requestedRole,
                            URI.create("https://a2a-developer/.well-known/agent-card.json"),
                            URI.create("https://a2a-developer/a2a"), "JSONRPC", "1.0", "b".repeat(64),
                            List.of(expectedSkill), false, true));
            worker.registerActivitiesImplementations((A2aActivities.ReconcileDispatch) request -> {
                dispatch.set(request);
                return completed(expectedOutputContract);
            });
            worker.registerActivitiesImplementations((A2aActivities.ValidateArtifacts) request ->
                    new A2aActivities.ValidatedArtifacts(request.task().taskId(), List.of(
                            new A2aActivities.EvidenceReference("artifact-1",
                                    request.task().artifacts().getFirst().parts().getFirst().uri().toString(),
                                    "c".repeat(64), request.outputContract()))));
            environment.start();
            DelegationWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    DelegationWorkflow.class, WorkflowOptions.newBuilder()
                            .setWorkflowId("delegation/task-1/attempt-1/developer-1")
                            .setTaskQueue("a2a-delegation-test").build());

            DelegationWorkflow.Result result = workflow.run(new DelegationWorkflow.Request(
                    "task-1", "attempt-1", role + "-1", "supervisor", role,
                    "a".repeat(40), "d".repeat(64)));

            assertThat(result.nodeId()).isEqualTo(role + "-1");
            assertThat(result.role()).isEqualTo(role);
            assertThat(result.status()).isEqualTo("READY_FOR_ACTIVITIES");
            assertThat(result.artifacts()).singleElement().satisfies(reference -> {
                assertThat(reference.artifactId()).isEqualTo("artifact-1");
                assertThat(reference.contract()).isEqualTo(expectedOutputContract);
                assertThat(reference.digest()).isEqualTo("c".repeat(64));
            });
            assertThat(dispatch.get().command().skillId()).isEqualTo(expectedSkill);
            Map<String, Object> envelope = dispatch.get().command().parts().getFirst().data();
            assertThat(envelope).containsEntry("target_role", role)
                    .containsEntry("skill_id", expectedSkill);
            assertThat(envelope.get("input_references").toString()).contains(
                    "evidence://task-1/attempt-1/delegation-input/" + role + "-1.json");
            assertThat(envelope.get("constraints").toString()).contains(expectedOutputContract);
            assertThat(dispatch.get().execution().workflowId()).contains("delegation/task-1");
            assertThat(dispatch.get().execution().delegationId())
                    .isEqualTo(TemporalIds.delegation("task-1", "attempt-1", role + "-1"));
            assertThat(dispatch.get().execution().parentDelegationId()).isNull();
            assertThat(dispatch.get().command().metadata().toString()).doesNotContain("prompt", "result");
        }
    }

    private static A2aContracts.TaskSnapshot completed(String outputContract) {
        String uri = "evidence://task-1/attempt-1/agent-result/result.json";
        A2aContracts.Part part = new A2aContracts.Part(A2aMediaTypes.EVIDENCE_REFERENCE, null,
                Map.of("uri", uri, "digest", "c".repeat(64), "contract", outputContract),
                URI.create(uri));
        return new A2aContracts.TaskSnapshot("remote-task-1", "remote-context-1",
                A2aContracts.TaskState.COMPLETED, Instant.EPOCH,
                List.of(new A2aContracts.Artifact("artifact-1", "result", List.of(part), Map.of())),
                Map.of("sequence", 1L));
    }
}
