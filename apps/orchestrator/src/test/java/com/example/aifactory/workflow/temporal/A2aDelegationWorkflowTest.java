package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aMediaTypes;
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

class A2aDelegationWorkflowTest {
    @Test
    void executesSpecialistOnlyThroughA2aActivitiesAndValidatesItsArtifact() {
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            Worker worker = environment.newWorker("a2a-delegation-test");
            worker.registerWorkflowImplementationTypes(A2aDelegationWorkflowImpl.class);
            AtomicReference<A2aActivities.DispatchRequest> dispatch = new AtomicReference<>();
            worker.registerActivitiesImplementations((A2aActivities.ResolveAgent) role ->
                    new A2aContracts.AgentCardDescriptor(role,
                            URI.create("https://a2a-developer/.well-known/agent-card.json"),
                            URI.create("https://a2a-developer/a2a"), "JSONRPC", "1.0", "b".repeat(64),
                            List.of("developer.code-task-v1"), false, true));
            worker.registerActivitiesImplementations((A2aActivities.ReconcileDispatch) request -> {
                dispatch.set(request);
                return completed();
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
                    "task-1", "attempt-1", "developer-1", "supervisor", "developer",
                    "a".repeat(40), "d".repeat(64)));

            assertThat(result).isEqualTo(new DelegationWorkflow.Result(
                    "developer-1", "developer", "READY_FOR_ACTIVITIES"));
            assertThat(dispatch.get().command().skillId()).isEqualTo("developer.code-task-v1");
            assertThat(dispatch.get().execution().workflowId()).contains("delegation/task-1");
            assertThat(dispatch.get().command().metadata().toString()).doesNotContain("prompt", "result");
        }
    }

    private static A2aContracts.TaskSnapshot completed() {
        String uri = "evidence://task-1/attempt-1/agent-result/result.json";
        A2aContracts.Part part = new A2aContracts.Part(A2aMediaTypes.EVIDENCE_REFERENCE, null,
                Map.of("uri", uri, "digest", "c".repeat(64), "contract", "patch-proposal-v1"),
                URI.create(uri));
        return new A2aContracts.TaskSnapshot("remote-task-1", "remote-context-1",
                A2aContracts.TaskState.COMPLETED, Instant.EPOCH,
                List.of(new A2aContracts.Artifact("artifact-1", "result", List.of(part), Map.of())),
                Map.of("sequence", 1L));
    }
}
