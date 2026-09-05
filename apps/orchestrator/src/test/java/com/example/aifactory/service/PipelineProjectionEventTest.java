package com.example.aifactory.service;

import com.example.aifactory.model.PendingEffect;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineProjectionEventTest {
    @Test
    void explicitEventsAreTheOnlyWayStepsUpdateTheTaskProjection() throws Exception {
        TaskState state = new TaskState("task-1", "AF-0001",
                new TaskRequest("https://example.test/repo.git", "main", "change", null));
        var metadata = new PipelineProjectionEvent.AgentMetadata(Map.of("planner", "a".repeat(64)), 12, 34, 1);
        PendingEffect effect = new PendingEffect("scm.create_draft_pull_request", Map.of("base_branch", "main"),
                "creates a draft PR", "ALLOW", true);

        PipelineProjectionEvent.Applier.apply(state, new PipelineProjectionEvent.WorkspaceInitialized("/workspace/task-1"));
        PipelineProjectionEvent.Applier.apply(state, new PipelineProjectionEvent.SourceCloned("b".repeat(40), "model"));
        PipelineProjectionEvent.Applier.apply(state, new PipelineProjectionEvent.PlanProduced("plan", metadata));
        PipelineProjectionEvent.Applier.apply(state, new PipelineProjectionEvent.PatchProduced("patch", 2, metadata));
        PipelineProjectionEvent.Applier.apply(state, new PipelineProjectionEvent.TestsCompleted(
                "tests", Map.of("tests", Map.of("verdict", "PASSED")), metadata));
        PipelineProjectionEvent.Applier.apply(state, new PipelineProjectionEvent.QualityCompleted(
                "quality", Map.of("quality", Map.of("verdict", "PASSED"))));
        PipelineProjectionEvent.Applier.apply(state, new PipelineProjectionEvent.SecurityCompleted(
                "security", Map.of("security", Map.of("verdict", "PASSED"))));
        PipelineProjectionEvent.Applier.apply(state, new PipelineProjectionEvent.ReviewCompleted("review", metadata));
        PipelineProjectionEvent.Applier.apply(state, new PipelineProjectionEvent.DeliveryPrepared(effect));
        PipelineProjectionEvent.Applier.apply(state, new PipelineProjectionEvent.PullRequestCreated("https://example.test/pr/1"));

        assertThat(state.workspace).isEqualTo("/workspace/task-1");
        assertThat(state.sourceCommit).isEqualTo("b".repeat(40));
        assertThat(state.plan).isEqualTo("plan");
        assertThat(state.patch).isEqualTo("patch");
        assertThat(state.testSummary).isEqualTo("tests");
        assertThat(state.qualitySummary).isEqualTo("quality");
        assertThat(state.securitySummary).isEqualTo("security");
        assertThat(state.review).isEqualTo("review");
        assertThat(state.pendingEffect).isEqualTo(effect);
        assertThat(state.pullRequestUrl).isEqualTo("https://example.test/pr/1");
        assertThat(state.patchRepairs).isEqualTo(2);
        assertThat(state.llmTokens).isEqualTo(48);
        assertThat(state.llmCostMicros).isEqualTo(136);
        assertThat(state.agentTurns).isEqualTo(4);

        String stepSource = Files.readString(Path.of("src/main/java/com/example/aifactory/service/PipelineStepService.java"));
        assertThat(stepSource).doesNotContain("state.plan =", "state.patch =", "state.testSummary =",
                "state.qualitySummary =", "state.securitySummary =", "state.review =",
                "state.pendingEffect =", "state.pullRequestUrl =", "state.recordAgentUsage(");
    }
}
