package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchIntegratorTest {
    @TempDir Path workspace;

    @Test
    void normalizesValidatesAndAppliesAnImmutablePatchArtifact() throws Exception {
        FakeSandbox sandbox = new FakeSandbox();
        PatchIntegrator integrator = new PatchIntegrator(sandbox);

        PatchIntegrator.IntegratedPatch patch = integrator.validate(workspace, "task-1", "a".repeat(40),
                "proposal\n```diff\ndiff --git a/a.txt b/a.txt\n--- a/a.txt\n+++ b/a.txt\n@@ -1 +1 @@\n-old\n+new\n```");

        assertThat(patch.content()).startsWith("diff --git").endsWith("\n");
        assertThat(patch.digest()).hasSize(64);
        assertThat(sandbox.validations).isEqualTo(1);
        assertThat(integrator.apply(workspace, "task-1", "a".repeat(40), patch)).isEqualTo("applied");
        assertThat(sandbox.applications).isEqualTo(1);
    }

    @Test
    void rejectsMutationBetweenValidationAndApplication() throws Exception {
        FakeSandbox sandbox = new FakeSandbox();
        PatchIntegrator integrator = new PatchIntegrator(sandbox);
        PatchIntegrator.IntegratedPatch patch = integrator.validate(workspace, "task-1", "a".repeat(40),
                "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1 +1 @@\n-a\n+b");
        Files.writeString(workspace.resolve("changes.patch"), "tampered");

        assertThatThrownBy(() -> integrator.apply(workspace, "task-1", "a".repeat(40), patch))
                .hasMessageContaining("changed after deterministic validation");
        assertThat(sandbox.applications).isZero();
    }

    private static final class FakeSandbox implements SandboxExecutor {
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

        @Override public String test(Path workspace, String taskId, String sourceCommit) { throw new UnsupportedOperationException(); }
        @Override public String quality(Path workspace, String taskId, String sourceCommit) { throw new UnsupportedOperationException(); }
        @Override public String security(Path workspace, String taskId, String sourceCommit) { throw new UnsupportedOperationException(); }
    }
}
