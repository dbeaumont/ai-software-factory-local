package com.example.aifactory.service;

import com.example.aifactory.workflow.EvidenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodePatchArtifactPublisherTest {
    @TempDir Path temporaryDirectory;
    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void returnsOnlyImmutablePatchMetadataAndNeverMutatesTheIntegrationWorkspace() throws Exception {
        Path repository = repository();
        String commit = runner.run(List.of("git", "rev-parse", "HEAD"), repository, Duration.ofSeconds(5)).strip();
        CodeWorkspaceManager worktrees = new CodeWorkspaceManager(runner);
        CodeWorkspaceManager.Allocation allocation = worktrees.create(
                repository, temporaryDirectory.resolve("isolated"),
                new CodeWorkspaceManager.Request("task-1", "attempt-1", "developer-a", commit));
        Path integrationWorkspace = temporaryDirectory.resolve("integration");
        Files.createDirectories(integrationWorkspace);
        Files.writeString(integrationWorkspace.resolve("sentinel.txt"), "unchanged\n");
        RecordingEvidenceRepository evidence = new RecordingEvidenceRepository();
        CodePatchArtifactPublisher publisher = new CodePatchArtifactPublisher(worktrees, evidence);

        CodePatchArtifactPublisher.PatchArtifact artifact = publisher.publish(allocation,
                "```diff\ndiff --git a/app.txt b/app.txt\n--- a/app.txt\n+++ b/app.txt\n@@ -1 +1 @@\n-baseline\n+changed\n```");

        assertThat(artifact.worktreeId()).isEqualTo(allocation.worktreeId());
        assertThat(artifact.sourceCommit()).isEqualTo(commit);
        assertThat(artifact.uri()).startsWith("evidence://task-1/attempt-1/code-patch/");
        assertThat(artifact.digest()).hasSize(64).isEqualTo(evidence.request.digest());
        assertThat(artifact.mediaType()).isEqualTo("text/x-diff");
        assertThat(Files.readString(integrationWorkspace.resolve("sentinel.txt"))).isEqualTo("unchanged\n");
        assertThat(integrationWorkspace.resolve("changes.patch")).doesNotExist();
        assertThat(java.util.Arrays.stream(CodePatchArtifactPublisher.PatchArtifact.class.getRecordComponents())
                .noneMatch(component -> component.getType() == byte[].class || component.getType() == String.class
                        && "content".equals(component.getName()))).isTrue();
    }

    private Path repository() throws Exception {
        Path repository = temporaryDirectory.resolve("source");
        Files.createDirectories(repository);
        runner.run(List.of("git", "init"), repository, Duration.ofSeconds(5));
        runner.run(List.of("git", "config", "user.email", "test@example.test"),
                repository, Duration.ofSeconds(5));
        runner.run(List.of("git", "config", "user.name", "Test"), repository, Duration.ofSeconds(5));
        Files.writeString(repository.resolve("app.txt"), "baseline\n");
        runner.run(List.of("git", "add", "app.txt"), repository, Duration.ofSeconds(5));
        runner.run(List.of("git", "commit", "-m", "baseline"), repository, Duration.ofSeconds(5));
        return repository;
    }

    private static final class RecordingEvidenceRepository implements EvidenceRepository {
        private StoreRequest request;

        @Override
        public StoredEvidence store(StoreRequest request) {
            this.request = request;
            return new StoredEvidence("evidence://" + request.taskId() + '/' + request.attemptId() + '/'
                    + request.type() + '/' + request.digest(), request.digest(), "COMPLETE", request.mediaType(),
                    request.content().length, "INTERNAL", Instant.parse("2026-10-02T00:00:00Z"),
                    Instant.parse("2026-09-02T00:00:00Z"));
        }

        @Override public StoredManifest createManifest(ManifestRequest request) {
            throw new UnsupportedOperationException();
        }
        @Override public EvidenceSummary getSummary(String taskId, String attemptId, String uri, String actor) {
            throw new UnsupportedOperationException();
        }
        @Override public RawEvidence read(ReadRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
