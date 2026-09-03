package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatchProposalValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PatchProposalValidator validator = new PatchProposalValidator(new PatchScopeValidator());
    private static final String PATCH = """
            diff --git a/src/App.java b/src/App.java
            --- a/src/App.java
            +++ b/src/App.java
            @@ -1 +1 @@
            -old
            +new
            """;

    @Test
    void validatesFormatDigestByteSizePathsOperationsAndAssignedScope() throws Exception {
        String normalized = PatchIntegrator.normalize(PATCH);
        JsonNode proposal = proposal(normalized, "src/App.java", "MODIFY");

        PatchProposalValidator.ValidatedPatch validated = validator.validate(
                codeTask(10_000, "src"), proposal, PATCH);

        assertThat(validated.digest()).isEqualTo(PatchIntegrator.digestFor(normalized));
        assertThat(validated.sizeBytes()).isEqualTo(normalized.getBytes(StandardCharsets.UTF_8).length);
        assertThat(validated.changes()).containsExactly(
                new PatchProposalValidator.FileChange("src/App.java", null, "MODIFY"));
    }

    @Test
    void rejectsMetadataDigestSizePathOperationAndScopeDivergence() throws Exception {
        String normalized = PatchIntegrator.normalize(PATCH);
        var wrongDigest = proposal(normalized, "src/App.java", "MODIFY").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) wrongDigest).put("patch_digest", "0".repeat(64));
        assertThatThrownBy(() -> validator.validate(codeTask(10_000, "src"), wrongDigest, PATCH))
                .hasMessageContaining("digest differs");

        var wrongSize = proposal(normalized, "src/App.java", "MODIFY").deepCopy();
        ((tools.jackson.databind.node.ObjectNode) wrongSize.path("diff_artifact")).put("size_bytes", 1);
        assertThatThrownBy(() -> validator.validate(codeTask(10_000, "src"), wrongSize, PATCH))
                .hasMessageContaining("size differs");

        assertThatThrownBy(() -> validator.validate(codeTask(10_000, "src"),
                proposal(normalized, "src/Other.java", "MODIFY"), PATCH))
                .hasMessageContaining("changed paths or operations differ");
        assertThatThrownBy(() -> validator.validate(codeTask(10_000, "src"),
                proposal(normalized, "src/App.java", "DELETE"), PATCH))
                .hasMessageContaining("changed paths or operations differ");
        assertThatThrownBy(() -> validator.validate(codeTask(10_000, "other"),
                proposal(normalized, "src/App.java", "MODIFY"), PATCH))
                .hasMessageContaining("outside assigned write scope");
        assertThatThrownBy(() -> validator.validate(codeTask(8, "src"),
                proposal(normalized, "src/App.java", "MODIFY"), PATCH))
                .hasMessageContaining("patch byte limit exceeded");
    }

    @Test
    void rejectsMalformedOrAmbiguousDiffHeaders() throws Exception {
        String ambiguous = "diff --git a/src/My File.java b/src/My File.java\n--- a/x\n+++ b/x\n";
        assertThatThrownBy(() -> validator.validate(codeTask(10_000, "src"),
                proposal(PatchIntegrator.normalize(ambiguous), "src/My File.java", "MODIFY"), ambiguous))
                .hasMessageContaining("unsupported or ambiguous path");
        assertThatThrownBy(() -> validator.validate(codeTask(10_000, "src"),
                proposal("not a diff\n", "src/App.java", "MODIFY"), "not a diff\n"))
                .hasMessageContaining("no file diff");
    }

    @Test
    void convertsAnInvalidBlankContextLineIntoAnAddedBlankLineWhenTheWorkspaceRequiresIt(@TempDir Path workspace)
            throws Exception {
        Path source = workspace.resolve("src/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                class App {
                    void first() {
                    }
                }
                """);
        String invalid = "diff --git a/src/App.java b/src/App.java\n"
                + "--- a/src/App.java\n"
                + "+++ b/src/App.java\n"
                + "@@ -4,2 +4,4 @@\n"
                + " \n"
                + "+    void second() {\n"
                + "+    }\n"
                + " }\n";

        String normalized = WorkspaceDiffNormalizer.normalize(workspace, invalid);

        assertThat(normalized).contains("@@ -4 +4,4 @@\n+\n+    void second() {");
    }

    private JsonNode codeTask(long maxBytes, String writeRoot) throws Exception {
        return mapper.readTree("""
                {"scope":{"write_paths":["%s"],"forbidden_paths":[],
                "max_changed_files":4,"max_patch_bytes":%d}}
                """.formatted(writeRoot, maxBytes));
    }

    private JsonNode proposal(String normalizedPatch, String path, String operation) throws Exception {
        String digest = PatchIntegrator.digestFor(normalizedPatch);
        int size = normalizedPatch.getBytes(StandardCharsets.UTF_8).length;
        return mapper.readTree("""
                {"patch_digest":"%s","diff_artifact":{"digest":"%s","size_bytes":%d},
                "files_touched":[{"path":"%s","operation":"%s"}]}
                """.formatted(digest, digest, size, path, operation));
    }
}
