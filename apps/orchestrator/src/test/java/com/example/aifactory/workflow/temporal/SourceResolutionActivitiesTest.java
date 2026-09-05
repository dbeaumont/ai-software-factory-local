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
            contextWorker.registerActivitiesImplementations((SourceResolutionActivities) request ->
                    new SourceResolutionActivities.Result(request.repositoryId(), request.branch(), "c".repeat(40),
                            "/workspace/" + request.taskId(), "d".repeat(64)));
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
            assertThat(result.chronology()).containsExactly("WORKFLOW_STARTED");
        }
    }
}
