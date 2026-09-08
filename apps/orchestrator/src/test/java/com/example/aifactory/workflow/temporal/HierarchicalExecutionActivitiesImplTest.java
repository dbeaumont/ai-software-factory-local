package com.example.aifactory.workflow.temporal;

import com.example.aifactory.model.LlmMode;
import com.example.aifactory.model.PendingEffect;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskRoutingFacts;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.service.MultiAgentContractValidator;
import com.example.aifactory.workflow.EvidenceRepository;
import com.example.aifactory.workflow.TaskMemory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HierarchicalExecutionActivitiesImplTest {
    @Test
    void persistsAValidatedAndAttemptBoundSpecialistTask() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        TaskMemory memory = mock(TaskMemory.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        TaskState state = new TaskState("task-1", "AF-0001", new TaskRequest(
                "http://gitea/customer-api.git", "main", "Assess the cross-module change",
                LlmMode.CLOUD, TaskRoutingFacts.qualifiedLowRiskFixture()));
        state.sourceCommit = "a".repeat(40);
        when(memory.find("task-1")).thenReturn(Optional.of(state));
        when(evidence.store(any())).thenAnswer(invocation -> {
            EvidenceRepository.StoreRequest stored = invocation.getArgument(0);
            return new EvidenceRepository.StoredEvidence(
                    "evidence://task-1/attempt-1/specialist-task/architecture", stored.digest(),
                    "COMPLETE", "application/json", stored.content().length, "INTERNAL",
                    Instant.parse("2026-10-08T00:00:00Z"), Instant.parse("2026-09-08T00:00:00Z"));
        });
        var activities = new HierarchicalExecutionActivitiesImpl(memory, evidence,
                new MultiAgentContractValidator(mapper), mapper,
                Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC));

        var part = activities.prepareSpecialistTask(new HierarchicalExecutionActivities.PrepareSpecialistTask(
                "task-1", "attempt-1", "customer-api", "a".repeat(40), "plan-1", "architecture",
                "supervisor", "architecture-agent", List.of(), Set.of("."), Set.of(),
                Set.of("context.list_tree", "context.search_code"),
                new DelegationWorkflow.Budget(10_000, 10_000_000, 6, 600),
                List.of("Return a source-bound architecture assessment")));

        assertThat(part.data()).containsEntry("contract", "specialist-task-v1")
                .containsEntry("digest", part.data().get("digest"));
        ArgumentCaptor<EvidenceRepository.StoreRequest> stored =
                ArgumentCaptor.forClass(EvidenceRepository.StoreRequest.class);
        verify(evidence).store(stored.capture());
        var document = mapper.readTree(stored.getValue().content());
        assertThat(document.path("role").asText()).isEqualTo("architecture-agent");
        assertThat(document.path("objective").asText()).isEqualTo("Assess the cross-module change");
        assertThat(document.path("deadline").asText()).isEqualTo("2026-09-08T00:10:00Z");
        assertThat(document.path("scope").path("read_paths").get(0).asText()).isEqualTo(".");
        assertThat(stored.getValue().digest()).isEqualTo(part.data().get("digest"));
    }

    @Test
    void validatesAndActivatesTheCodeAgentIntegrationPlan() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        TaskMemory memory = mock(TaskMemory.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        TaskState state = new TaskState("task-1", "AF-0001", new TaskRequest(
                "http://gitea/customer-api.git", "main", "Implement the cross-module change",
                LlmMode.CLOUD, TaskRoutingFacts.qualifiedLowRiskFixture()));
        state.sourceCommit = "a".repeat(40);
        when(memory.find("task-1")).thenReturn(Optional.of(state));
        var fixture = java.nio.file.Path.of(System.getProperty("user.dir"))
                .resolve("../../resources/multiagents/fixtures/golden-contracts-v1.json").normalize();
        byte[] content = mapper.writeValueAsBytes(mapper.readTree(java.nio.file.Files.readString(fixture))
                .path("documents").path("integration-proposal-v1"));
        String digest = TemporalIds.sha256(new String(content, java.nio.charset.StandardCharsets.UTF_8));
        String uri = "evidence://task-1/attempt-1/agent-result/" + digest;
        when(evidence.read(any())).thenReturn(new EvidenceRepository.RawEvidence(
                uri, "agent-result", digest, "COMPLETE", "INTERNAL", content));
        var activities = new HierarchicalExecutionActivitiesImpl(memory, evidence,
                new MultiAgentContractValidator(mapper), mapper,
                Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC));

        var accepted = activities.acceptSpecialistResult(
                new HierarchicalExecutionActivities.AcceptSpecialistResult(
                        "task-1", "attempt-1", "a".repeat(40), "code-agent",
                        "integration-proposal-v1",
                        new A2aActivities.EvidenceReference("artifact-1", uri, digest,
                                "integration-proposal-v1"),
                        Set.of("plan-1", "node-1", "assessment-1"), true));

        assertThat(accepted.documentId()).isEqualTo("integration-proposal-1");
        assertThat(accepted.artifact().digest()).isEqualTo(digest);
        assertThat(state.plan).contains("integration-proposal-1", "developer_tasks");
        verify(memory).project("hierarchical-result:code-agent:" + digest, state);
    }

    @Test
    void preparesASourceBoundManifestAndBundleForIndependentReview() {
        ObjectMapper mapper = JsonMapper.builder().build();
        TaskMemory memory = mock(TaskMemory.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        TaskState state = new TaskState("task-1", "AF-0001", new TaskRequest(
                "http://gitea/customer-api.git", "main", "Implement the cross-module change",
                LlmMode.CLOUD, TaskRoutingFacts.qualifiedLowRiskFixture()));
        state.sourceCommit = "a".repeat(40);
        state.patch = "diff --git a/src/Old.java b/src/Main.java\n--- a/src/Old.java\n+++ b/src/Main.java\n";
        state.pendingEffect = new PendingEffect("scm.create_draft_pull_request", Map.of(),
                "Create draft pull request", "ALLOW", true);
        when(memory.find("task-1")).thenReturn(Optional.of(state));
        when(evidence.createManifest(any())).thenReturn(new EvidenceRepository.StoredManifest(
                "b".repeat(64), "evidence://task-1/attempt-1/manifest/" + "b".repeat(64),
                "c".repeat(64), "COMPLETE", "CONFIDENTIAL",
                Instant.parse("2027-09-08T00:00:00Z"), Instant.parse("2026-09-08T00:00:00Z")));
        var activities = new HierarchicalExecutionActivitiesImpl(memory, evidence,
                new MultiAgentContractValidator(mapper), mapper,
                Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC));
        Map<String, com.example.aifactory.service.PipelineStepContracts.ArtifactReference> artifacts =
                new LinkedHashMap<>();
        for (String name : List.of("plan", "patch", "tests", "quality", "security")) {
            artifacts.put(name, artifact(name));
        }
        List<HierarchicalExecutionActivities.ReviewedSpecialistResult> results = List.of(
                reviewed("assessment-1", "architecture-agent"),
                reviewed("integration-proposal-1", "code-agent"),
                reviewed("test-strategy-1", "test-design"),
                reviewed("test-assessment-1", "test-agent"),
                reviewed("security-assessment-1", "security-agent"));

        var prepared = activities.prepareIndependentReview(
                new HierarchicalExecutionActivities.PrepareIndependentReview(
                        "task-1", "attempt-1", "customer-api", "a".repeat(40), artifacts, results));

        assertThat(prepared.bundle().consolidatedPatch().changedFiles()).containsExactly("src/Main.java");
        assertThat(prepared.bundle().reviewedResults()).extracting(
                com.example.aifactory.service.IndependentReviewBundle.ResultReference::resultId)
                .containsExactly("assessment-1", "integration-proposal-1", "test-strategy-1",
                        "test-assessment-1", "security-assessment-1");
        assertThat(prepared.bundle().reviewedArtifactDigests()).containsOnlyKeys(
                "plan", "patch", "tests", "quality", "security");
        assertThat(state.pendingEffect.manifestId()).isEqualTo("b".repeat(64));
        verify(memory).project("hierarchical-review-manifest:" + "c".repeat(64), state);
    }

    private static com.example.aifactory.service.PipelineStepContracts.ArtifactReference artifact(String name) {
        String digest = TemporalIds.sha256(name);
        return new com.example.aifactory.service.PipelineStepContracts.ArtifactReference(
                "evidence://task-1/attempt-1/" + name + '/' + digest,
                digest, 64, "COMPLETE", "ACCEPTED");
    }

    private static HierarchicalExecutionActivities.ReviewedSpecialistResult reviewed(String id, String role) {
        return new HierarchicalExecutionActivities.ReviewedSpecialistResult(id, role, artifact(role));
    }
}
