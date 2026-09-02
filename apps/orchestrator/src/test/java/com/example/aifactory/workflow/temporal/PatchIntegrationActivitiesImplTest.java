package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.PatchIntegrationPlanner;
import com.example.aifactory.service.PatchIntegrator;
import com.example.aifactory.service.SandboxExecutor;
import com.example.aifactory.service.CodeWorkspaceManager;
import com.example.aifactory.service.ProcessRunner;
import com.example.aifactory.workflow.EvidenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
class PatchIntegrationActivitiesImplTest {
    @TempDir Path workspace;

    @Test
    void readsOrderedEvidenceAndAppliesOneConsolidatedPatchThroughTheSandbox() throws Exception {
        String first = patch("a.txt");
        String second = patch("b.txt");
        String firstDigest = PatchIntegrator.digestFor(first);
        String secondDigest = PatchIntegrator.digestFor(second);
        PatchIntegrationActivities.PatchArtifact a = artifact("node-a", "proposal-a", firstDigest);
        PatchIntegrationActivities.PatchArtifact b = artifact("node-b", "proposal-b", secondDigest);
        EvidenceRepository evidence = new FakeEvidence(Map.of(a.uri(), raw(a, first), b.uri(), raw(b, second)));
        FakeSandbox sandbox = new FakeSandbox();
        PatchIntegrationActivitiesImpl activities = new PatchIntegrationActivitiesImpl(
                evidence, new PatchIntegrator(sandbox), sandbox,
                new CodeWorkspaceManager(new ProcessRunner()));

        PatchIntegrationActivities.ApplicationResult result = activities.apply(new PatchIntegrationActivities.Request(
                DurableExecutionActivities.Metadata.deterministic("task-1", "attempt-1", "a".repeat(40),
                        "integration", "apply-patches", 1), workspace.toString(), plan(a, b),
                PatchIntegrationWorkflow.PATCH_CHECK_PROFILE, PatchIntegrationWorkflow.PATCH_APPLY_PROFILE,
                List.of(a, b)));

        assertThat(result.status()).isEqualTo("APPLIED");
        assertThat(result.integratedPatchDigest()).isEqualTo(PatchIntegrator.digestFor(first + second));
        assertThat(Files.readString(workspace.resolve("changes.patch"))).isEqualTo(first + second);
        assertThat(sandbox.validations).isEqualTo(1);
        assertThat(sandbox.applications).isEqualTo(1);

        for (PatchIntegrationActivities.VerificationKind kind
                : PatchIntegrationActivities.VerificationKind.values()) {
            PatchIntegrationActivities.VerificationResult verification = activities.verify(
                    new PatchIntegrationActivities.VerificationRequest(
                            DurableExecutionActivities.Metadata.deterministic(
                                    "task-1", "attempt-1", "a".repeat(40), "integration",
                                    "verify-" + kind.name().toLowerCase(), kind.ordinal() + 2),
                            workspace.toString(), result.integratedPatchDigest(), kind));
            assertThat(verification.status()).isEqualTo("PASSED");
        }
        assertThat(sandbox.tests).isEqualTo(1);
        assertThat(sandbox.qualityScans).isEqualTo(1);
        assertThat(sandbox.securityScans).isEqualTo(1);
    }

    @Test
    void cleanupRemovesWorktreesAndTerminalTemporaryPatchDataForEveryOutcome() throws Exception {
        ProcessRunner runner = new ProcessRunner();
        Path repository = workspace.resolve("source");
        Files.createDirectories(repository);
        runner.run(List.of("git", "init"), repository, Duration.ofSeconds(5));
        runner.run(List.of("git", "config", "user.email", "test@example.test"),
                repository, Duration.ofSeconds(5));
        runner.run(List.of("git", "config", "user.name", "Test"),
                repository, Duration.ofSeconds(5));
        Files.writeString(repository.resolve("app.txt"), "baseline\n");
        runner.run(List.of("git", "add", "app.txt"), repository, Duration.ofSeconds(5));
        runner.run(List.of("git", "commit", "-m", "baseline"), repository, Duration.ofSeconds(5));
        String commit = runner.run(List.of("git", "rev-parse", "HEAD"),
                repository, Duration.ofSeconds(5)).strip();
        CodeWorkspaceManager manager = new CodeWorkspaceManager(runner);
        PatchIntegrationActivitiesImpl activities = new PatchIntegrationActivitiesImpl(
                new FakeEvidence(Map.of()), new PatchIntegrator(new FakeSandbox()),
                new FakeSandbox(), manager);

        for (PatchIntegrationActivities.TerminalOutcome outcome
                : PatchIntegrationActivities.TerminalOutcome.values()) {
            String suffix = outcome.name().toLowerCase();
            Path isolation = workspace.resolve("isolated-" + suffix);
            Path integration = workspace.resolve("integration-" + suffix);
            Files.createDirectories(integration);
            Files.writeString(integration.resolve("changes.patch"), "consolidated");
            Files.writeString(integration.resolve("changes.invalid.patch"), "invalid");
            CodeWorkspaceManager.Allocation allocation = manager.create(repository, isolation,
                    new CodeWorkspaceManager.Request("task-1", "attempt-1", "developer-" + suffix, commit));
            PatchIntegrationActivities.CleanupPlan plan = new PatchIntegrationActivities.CleanupPlan(
                    repository.toString(), isolation.toString(), integration.toString(), List.of(
                    new PatchIntegrationActivities.WorktreeRef(allocation.worktreeId(), allocation.taskId(),
                            allocation.attemptId(), allocation.nodeId(), allocation.sourceCommit(),
                            allocation.path().toString())));

            PatchIntegrationActivities.CleanupResult result = activities.cleanup(
                    new PatchIntegrationActivities.CleanupRequest(
                            DurableExecutionActivities.Metadata.deterministic(
                                    "task-1", "attempt-1", commit, "integration", "cleanup", 5),
                            plan, outcome));

            assertThat(result.status()).isEqualTo("CLEANED");
            assertThat(allocation.path()).doesNotExist();
            assertThat(integration.resolve("changes.invalid.patch")).doesNotExist();
            if (outcome == PatchIntegrationActivities.TerminalOutcome.SUCCESS) {
                assertThat(integration.resolve("changes.patch")).exists();
            } else {
                assertThat(integration.resolve("changes.patch")).doesNotExist();
            }
        }
    }

    private static String plan(PatchIntegrationActivities.PatchArtifact... artifacts) {
        return PatchIntegrationPlanner.digestIdentities(java.util.Arrays.stream(artifacts).map(artifact ->
                new PatchIntegrationPlanner.PatchIdentity(
                        artifact.nodeId(), artifact.proposalId(), artifact.digest())).toList());
    }

    private static String patch(String path) {
        return "diff --git a/" + path + " b/" + path + "\n--- a/" + path + "\n+++ b/" + path
                + "\n@@ -1 +1 @@\n-old\n+new\n";
    }

    private static PatchIntegrationActivities.PatchArtifact artifact(
            String nodeId, String proposalId, String digest) {
        return new PatchIntegrationActivities.PatchArtifact(nodeId, proposalId,
                "evidence://task-1/attempt-1/code-patch/" + digest, digest);
    }

    private static EvidenceRepository.RawEvidence raw(
            PatchIntegrationActivities.PatchArtifact artifact, String content) {
        return new EvidenceRepository.RawEvidence(artifact.uri(), "code-patch", artifact.digest(),
                "COMPLETE", "INTERNAL", content.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FakeSandbox implements SandboxExecutor {
        int validations;
        int applications;
        int tests;
        int qualityScans;
        int securityScans;

        @Override public String applyPatch(Path workspace, String taskId, String sourceCommit) {
            applications++;
            return "applied";
        }

        @Override public String checkPatch(Path workspace, String taskId, String sourceCommit) {
            validations++;
            return "valid";
        }

        @Override public String test(Path workspace, String taskId, String sourceCommit) {
            tests++;
            return "tests passed";
        }

        @Override public String quality(Path workspace, String taskId, String sourceCommit) {
            qualityScans++;
            return "quality passed";
        }

        @Override public String security(Path workspace, String taskId, String sourceCommit) {
            securityScans++;
            return "security passed";
        }
    }

    private static final class FakeEvidence implements EvidenceRepository {
        private final Map<String, RawEvidence> artifacts;

        private FakeEvidence(Map<String, RawEvidence> artifacts) {
            this.artifacts = new LinkedHashMap<>(artifacts);
        }

        @Override public RawEvidence read(ReadRequest request) {
            assertThat(request.actor()).isEqualTo("workflow");
            assertThat(request.purpose()).startsWith("apply-patch-integration:");
            RawEvidence artifact = artifacts.get(request.uri());
            if (artifact == null) throw new IllegalArgumentException("unknown evidence");
            return artifact;
        }

        @Override public StoredEvidence store(StoreRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override public StoredManifest createManifest(ManifestRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override public EvidenceSummary getSummary(String taskId, String attemptId, String uri, String actor) {
            throw new UnsupportedOperationException();
        }
    }
}
