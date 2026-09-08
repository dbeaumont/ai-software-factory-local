package com.example.aifactory.service;

import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PipelineStepContractsTest {
    @Test
    void commandBindsEveryStepToImmutableExecutionIdentityAndInputDigests() {
        TaskState state = new TaskState("task-1", "AF-0001",
                new TaskRequest("https://example.test/repo.git", "main", "change", null));
        state.sourceCommit = "a".repeat(40);

        var command = PipelineStepContracts.Command.forTask(state, "plan", Map.of("requirement", "change"));

        assertThat(command.schemaVersion()).isEqualTo(1);
        assertThat(command.taskId()).isEqualTo("task-1");
        assertThat(command.attemptId()).isEqualTo("attempt-1");
        assertThat(command.workflowId()).isEqualTo("ai-factory/task-1/attempt-1");
        assertThat(command.repositoryId()).isNotBlank();
        assertThat(command.sourceCommit()).isEqualTo("a".repeat(40));
        assertThat(command.inputDigests().get("requirement")).matches("[0-9a-f]{64}");
        assertThatThrownBy(() -> command.inputDigests().put("other", "b".repeat(64)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void commandRejectsUnsupportedSchemasMalformedDigestsAndOversizedValues() {
        assertThatThrownBy(() -> new PipelineStepContracts.Command(2, "plan", "task-1", "attempt-1",
                "ai-factory/task-1/attempt-1", "repository-1", "a".repeat(40), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PipelineStepContracts.Command(1, "plan", "task-1", "attempt-1",
                "ai-factory/task-1/attempt-1", "repository-1", "a".repeat(40), Map.of("plan", "invalid")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PipelineStepContracts.Command.forTask(
                new TaskState("task-1", "AF-0001",
                        new TaskRequest("https://example.test/repo.git", "main", "change", null)),
                "plan", Map.of("value", "x".repeat(1_000_001))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resultCarriesOnlyIdentityAndOutputDigests() {
        TaskState state = new TaskState("task-1", "AF-0001",
                new TaskRequest("https://example.test/repo.git", "main", "change", null));
        state.sourceCommit = "a".repeat(40);
        var command = PipelineStepContracts.Command.forTask(state, "review", Map.of("patch", "diff"));

        var reference = new PipelineStepContracts.ArtifactReference(
                "evidence://task-1/pipeline-1/review/" + "c".repeat(64), "c".repeat(64), 8,
                "COMPLETE", "ACCEPT");
        var result = PipelineStepContracts.Result.from(command, state.sourceCommit, Map.of("review", reference));

        assertThat(result.step()).isEqualTo("review");
        assertThat(result.taskId()).isEqualTo(command.taskId());
        assertThat(result.attemptId()).isEqualTo(command.attemptId());
        assertThat(result.artifacts().get("review")).isEqualTo(reference);
        assertThat(java.util.Arrays.stream(PipelineStepContracts.Result.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)).doesNotContain("content");
    }
}
