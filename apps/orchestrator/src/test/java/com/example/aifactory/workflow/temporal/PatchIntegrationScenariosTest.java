package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.CodeWorkspaceManager;
import com.example.aifactory.service.PatchConflictDetector;
import com.example.aifactory.service.PatchIntegrationPlanner;
import com.example.aifactory.service.PatchIntegrator;
import com.example.aifactory.service.PatchProposalValidator;
import com.example.aifactory.service.ProcessRunner;
import com.example.aifactory.service.SandboxExecutor;
import com.example.aifactory.workflow.EvidenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchIntegrationScenariosTest {
    @TempDir Path temporaryDirectory;
    private final PatchIntegrationPlanner planner = new PatchIntegrationPlanner(new PatchConflictDetector());

    @Test
    void twoDisjointPatchesReachOneConsolidatedSandboxExecution() throws Exception {
        RecordingSandbox sandbox = new RecordingSandbox();
        var first = candidate("code-a", "proposal-a", modify("src/A.java", 1));
        var second = candidate("code-b", "proposal-b", modify("src/B.java", 1));

        PatchIntegrationActivities.ApplicationResult result = execute(List.of(second, first), sandbox);

        assertThat(result.status()).isEqualTo("APPLIED");
        assertThat(sandbox.validations).isEqualTo(1);
        assertThat(sandbox.applications).isEqualTo(1);
    }

    @Test
    void twoPatchesWithOverlappingHunksAreRejectedBeforeSandboxExecution() {
        RecordingSandbox sandbox = new RecordingSandbox();
        var first = candidate("code-a", "proposal-a", modify("src/App.java", 10));
        var second = candidate("code-b", "proposal-b", modify("src/App.java", 10));

        assertThatThrownBy(() -> execute(List.of(first, second), sandbox))
                .hasMessageContaining("conflicting patches").hasMessageContaining("INCOMPATIBLE_HUNK");
        assertThat(sandbox.validations).isZero();
        assertThat(sandbox.applications).isZero();
    }

    @Test
    void divergentRenamesAreDetectedAsACollisionBeforeSandboxExecution() {
        RecordingSandbox sandbox = new RecordingSandbox();
        var first = candidate("code-a", "proposal-a", rename("src/OldA.java", "src/New.java"));
        var second = candidate("code-b", "proposal-b", rename("src/OldB.java", "src/New.java"));

        assertThatThrownBy(() -> execute(List.of(first, second), sandbox))
                .hasMessageContaining("conflicting patches").hasMessageContaining("RENAME_COLLISION");
        assertThat(sandbox.validations).isZero();
        assertThat(sandbox.applications).isZero();
    }

    private PatchIntegrationActivities.ApplicationResult execute(
            List<PatchIntegrationPlanner.PatchCandidate> candidates, RecordingSandbox sandbox) throws Exception {
        List<DelegationWorkflow.Request> dag = candidates.stream().map(candidate -> node(candidate.nodeId())).toList();
        PatchIntegrationPlanner.IntegrationPlan plan = planner.plan(root(), dag, candidates);
        Map<String, EvidenceRepository.RawEvidence> evidence = new LinkedHashMap<>();
        List<PatchIntegrationActivities.PatchArtifact> artifacts = plan.orderedPatches().stream().map(candidate -> {
            String uri = "evidence://task-1/attempt-1/code-patch/" + candidate.patch().digest();
            evidence.put(uri, new EvidenceRepository.RawEvidence(uri, "code-patch", candidate.patch().digest(),
                    "COMPLETE", "INTERNAL", candidate.patch().content().getBytes(StandardCharsets.UTF_8)));
            return new PatchIntegrationActivities.PatchArtifact(
                    candidate.nodeId(), candidate.proposalId(), uri, candidate.patch().digest());
        }).toList();
        Path workspace = Files.createDirectory(temporaryDirectory.resolve("integration-" + System.nanoTime()));
        PatchIntegrationActivitiesImpl activities = new PatchIntegrationActivitiesImpl(
                new FakeEvidence(evidence), new PatchIntegrator(sandbox), sandbox,
                new CodeWorkspaceManager(new ProcessRunner()));
        return activities.apply(new PatchIntegrationActivities.Request(
                DurableExecutionActivities.Metadata.deterministic(
                        "task-1", "attempt-1", "a".repeat(40), "integration", "apply-patches", 1),
                workspace.toString(), plan.digest(), PatchIntegrationWorkflow.PATCH_CHECK_PROFILE,
                PatchIntegrationWorkflow.PATCH_APPLY_PROFILE, artifacts));
    }

    private static SoftwareFactoryWorkflow.Request root() {
        return new SoftwareFactoryWorkflow.Request("task-1", "attempt-1", "a".repeat(40), "change");
    }

    private static DelegationWorkflow.Request node(String nodeId) {
        return new DelegationWorkflow.Request("task-1", "attempt-1", nodeId, "supervisor", "developer",
                "a".repeat(40), nodeId, 10, Set.of(), new DelegationWorkflow.Budget(100, 100, 1));
    }

    private static PatchIntegrationPlanner.PatchCandidate candidate(
            String nodeId, String proposalId, PatchProposalValidator.ValidatedPatch patch) {
        return new PatchIntegrationPlanner.PatchCandidate(nodeId, proposalId, patch);
    }

    private static PatchProposalValidator.ValidatedPatch modify(String path, int line) {
        String content = "diff --git a/" + path + " b/" + path + "\n--- a/" + path + "\n+++ b/" + path
                + "\n@@ -" + line + " +" + line + " @@\n-old\n+new\n";
        return validated(content, new PatchProposalValidator.FileChange(path, null, "MODIFY"));
    }

    private static PatchProposalValidator.ValidatedPatch rename(String previous, String path) {
        String content = "diff --git a/" + previous + " b/" + path + "\nsimilarity index 100%\nrename from "
                + previous + "\nrename to " + path + "\n";
        return validated(content, new PatchProposalValidator.FileChange(path, previous, "RENAME"));
    }

    private static PatchProposalValidator.ValidatedPatch validated(
            String content, PatchProposalValidator.FileChange change) {
        String normalized = PatchIntegrator.normalize(content);
        return new PatchProposalValidator.ValidatedPatch(normalized, PatchIntegrator.digestFor(normalized),
                normalized.getBytes(StandardCharsets.UTF_8).length, List.of(change));
    }

    private static final class RecordingSandbox implements SandboxExecutor {
        int validations;
        int applications;

        @Override public String applyPatch(Path workspace, String taskId, String sourceCommit) {
            applications++;
            return "applied";
        }

        @Override public String checkPatch(Path workspace, String taskId, String sourceCommit) {
            validations++;
            return "valid";
        }

        @Override public String test(Path workspace, String taskId, String sourceCommit) {
            throw new UnsupportedOperationException();
        }

        @Override public String quality(Path workspace, String taskId, String sourceCommit) {
            throw new UnsupportedOperationException();
        }

        @Override public String security(Path workspace, String taskId, String sourceCommit) {
            throw new UnsupportedOperationException();
        }
    }

    private record FakeEvidence(Map<String, RawEvidence> artifacts) implements EvidenceRepository {
        private FakeEvidence {
            artifacts = Map.copyOf(artifacts);
        }

        @Override public RawEvidence read(ReadRequest request) {
            RawEvidence evidence = artifacts.get(request.uri());
            if (evidence == null) throw new IllegalArgumentException("unknown evidence");
            return evidence;
        }

        @Override public StoredEvidence store(StoreRequest request) { throw new UnsupportedOperationException(); }
        @Override public StoredManifest createManifest(ManifestRequest request) { throw new UnsupportedOperationException(); }
        @Override public EvidenceSummary getSummary(String taskId, String attemptId, String uri, String actor) {
            throw new UnsupportedOperationException();
        }
    }
}
