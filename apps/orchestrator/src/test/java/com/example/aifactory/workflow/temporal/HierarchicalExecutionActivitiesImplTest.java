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
                reviewed("patch-proposal-1", "developer"),
                reviewed("test-strategy-1", "test-design"),
                reviewed("test-assessment-1", "test-agent"),
                reviewed("security-assessment-1", "security-agent"));

        var prepared = activities.prepareIndependentReview(
                new HierarchicalExecutionActivities.PrepareIndependentReview(
                        "task-1", "attempt-1", "customer-api", "a".repeat(40), artifacts, results));

        assertThat(prepared.bundle().consolidatedPatch().changedFiles()).containsExactly("src/Main.java");
        assertThat(prepared.bundle().reviewedResults()).extracting(
                com.example.aifactory.service.IndependentReviewBundle.ResultReference::resultId)
                .containsExactly("assessment-1", "integration-proposal-1", "patch-proposal-1", "test-strategy-1",
                        "test-assessment-1", "security-assessment-1");
        assertThat(prepared.bundle().reviewedArtifactDigests()).containsOnlyKeys(
                "plan", "patch", "tests", "quality", "security");
        assertThat(state.pendingEffect.manifestId()).isEqualTo("b".repeat(64));
        verify(memory).project("hierarchical-review-manifest:" + "c".repeat(64), state);
    }

    @Test
    void materializesDeveloperTasksFromAcceptedArchitectureAndCodeResults() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        TaskMemory memory = mock(TaskMemory.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        TaskState state = taskState("Implement the scoped change");
        when(memory.find("task-1")).thenReturn(Optional.of(state));
        var documents = goldenDocuments(mapper);
        var architecture = documents.path("architecture-assessment-v1").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) architecture).put("specialist_task_id", "specialist-architecture");
        var integration = documents.path("integration-proposal-v1").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) integration).put("node_id", "code");
        byte[] architectureContent = mapper.writeValueAsBytes(architecture);
        byte[] integrationContent = mapper.writeValueAsBytes(integration);
        String architectureDigest = digest(architectureContent);
        String integrationDigest = digest(integrationContent);
        when(evidence.read(any())).thenAnswer(invocation -> {
            EvidenceRepository.ReadRequest read = invocation.getArgument(0);
            boolean architectureRead = read.uri().contains("/architecture/");
            byte[] content = architectureRead ? architectureContent : integrationContent;
            String digest = architectureRead ? architectureDigest : integrationDigest;
            return new EvidenceRepository.RawEvidence(
                    read.uri(), "agent-result", digest, "COMPLETE", "INTERNAL", content);
        });
        when(evidence.store(any())).thenAnswer(invocation -> stored(invocation.getArgument(0), "code-task"));
        var activities = activities(memory, evidence, mapper);

        var prepared = activities.prepareDeveloperTasks(new HierarchicalExecutionActivities.PrepareDeveloperTasks(
                "task-1", "attempt-1", "customer-api", "a".repeat(40), "plan-1", "assessment-1",
                reference("architecture", architectureDigest, "architecture-assessment-v1"),
                reference("integration", integrationDigest, "integration-proposal-v1"),
                new DelegationWorkflow.Budget(12_000, 12_000_000, 6, 900)));

        assertThat(prepared).singleElement().satisfies(task -> {
            assertThat(task.nodeId()).isEqualTo("developer-1");
            assertThat(task.codeTaskId()).isEqualTo("code-1");
            assertThat(task.inputReference().data()).containsEntry("contract", "code-task-v1");
        });
        ArgumentCaptor<EvidenceRepository.StoreRequest> stored =
                ArgumentCaptor.forClass(EvidenceRepository.StoreRequest.class);
        verify(evidence).store(stored.capture());
        var codeTask = mapper.readTree(stored.getValue().content());
        assertThat(codeTask.path("scope").path("write_paths").get(0).asText()).isEqualTo("src/App.java");
        assertThat(codeTask.path("worktree_id").asText()).startsWith("worktree-developer-1-");
    }

    @Test
    void validatesAndProjectsNativeDeveloperPatchContent() throws Exception {
        ObjectMapper mapper = JsonMapper.builder().build();
        TaskMemory memory = mock(TaskMemory.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        TaskState state = taskState("Implement the scoped change");
        when(memory.find("task-1")).thenReturn(Optional.of(state));
        var documents = goldenDocuments(mapper);
        byte[] codeTaskContent = mapper.writeValueAsBytes(documents.path("code-task-v1"));
        String codeTaskDigest = digest(codeTaskContent);
        String patch = "diff --git a/src/App.java b/src/App.java\n--- a/src/App.java\n+++ b/src/App.java\n"
                + "@@ -1 +1 @@\n-old\n+new\n";
        String patchDigest = com.example.aifactory.service.PatchIntegrator.digestFor(patch);
        var proposal = documents.path("patch-proposal-v1").deepCopy();
        var proposalObject = (tools.jackson.databind.node.ObjectNode) proposal;
        proposalObject.put("patch", patch).put("patch_digest", patchDigest);
        ((tools.jackson.databind.node.ObjectNode) proposal.path("diff_artifact"))
                .put("uri", "evidence://task-1/attempt-1/code-patch/" + patchDigest)
                .put("digest", patchDigest).put("size_bytes", patch.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        byte[] proposalContent = mapper.writeValueAsBytes(proposal);
        String proposalDigest = digest(proposalContent);
        when(evidence.read(any())).thenAnswer(invocation -> {
            EvidenceRepository.ReadRequest read = invocation.getArgument(0);
            boolean codeTask = read.uri().contains("/code-task/");
            return new EvidenceRepository.RawEvidence(read.uri(), codeTask ? "code-task" : "agent-result",
                    codeTask ? codeTaskDigest : proposalDigest, "COMPLETE", "INTERNAL",
                    codeTask ? codeTaskContent : proposalContent);
        });
        when(evidence.store(any())).thenAnswer(invocation -> stored(invocation.getArgument(0), "code-patch"));
        var activities = activities(memory, evidence, mapper);
        var input = com.example.aifactory.a2a.A2aEvidencePartFactory.reference(
                "code-1", "evidence://task-1/attempt-1/code-task/" + codeTaskDigest,
                codeTaskDigest, "code-task-v1", codeTaskContent.length);
        var task = new HierarchicalExecutionActivities.DeveloperTask(
                "node-1", "code-1", Set.of(), input);

        var accepted = activities.acceptDeveloperPatches(new HierarchicalExecutionActivities.AcceptDeveloperPatches(
                "task-1", "attempt-1", "a".repeat(40), List.of(
                new HierarchicalExecutionActivities.DeveloperPatchResult(task,
                        new A2aActivities.EvidenceReference("proposal-1",
                                "evidence://task-1/attempt-1/agent-result/" + proposalDigest,
                                proposalDigest, "patch-proposal-v1")))));

        assertThat(accepted.patchCandidate().digest()).isEqualTo(patchDigest);
        assertThat(accepted.reviewedResults()).extracting(
                HierarchicalExecutionActivities.ReviewedSpecialistResult::documentId)
                .containsExactly("proposal-1");
        assertThat(state.patch).isEqualTo(patch);
        verify(memory).project("hierarchical-developer-patches:" + patchDigest, state);
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

    private static TaskState taskState(String requirement) {
        TaskState state = new TaskState("task-1", "AF-0001", new TaskRequest(
                "http://gitea/customer-api.git", "main", requirement,
                LlmMode.CLOUD, TaskRoutingFacts.qualifiedLowRiskFixture()));
        state.sourceCommit = "a".repeat(40);
        return state;
    }

    private static HierarchicalExecutionActivitiesImpl activities(
            TaskMemory memory, EvidenceRepository evidence, ObjectMapper mapper) {
        return new HierarchicalExecutionActivitiesImpl(memory, evidence,
                new MultiAgentContractValidator(mapper), mapper,
                Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC));
    }

    private static tools.jackson.databind.JsonNode goldenDocuments(ObjectMapper mapper) throws Exception {
        var fixture = java.nio.file.Path.of(System.getProperty("user.dir"))
                .resolve("../../resources/multiagents/fixtures/golden-contracts-v1.json").normalize();
        return mapper.readTree(java.nio.file.Files.readString(fixture)).path("documents");
    }

    private static A2aActivities.EvidenceReference reference(String name, String digest, String contract) {
        return new A2aActivities.EvidenceReference(name,
                "evidence://task-1/attempt-1/" + name + '/' + digest, digest, contract);
    }

    private static String digest(byte[] content) {
        return TemporalIds.sha256(new String(content, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static EvidenceRepository.StoredEvidence stored(EvidenceRepository.StoreRequest request, String type) {
        return new EvidenceRepository.StoredEvidence(
                "evidence://" + request.taskId() + '/' + request.attemptId() + '/' + type + '/' + request.digest(),
                request.digest(), "COMPLETE", request.mediaType(), request.content().length, "INTERNAL",
                Instant.parse("2027-09-08T00:00:00Z"), Instant.parse("2026-09-08T00:00:00Z"));
    }
}
