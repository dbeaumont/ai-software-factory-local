package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PatchConflictDetectorTest {
    private final PatchConflictDetector detector = new PatchConflictDetector();

    @Test
    void acceptsTwoPatchesTouchingDisjointFiles() {
        PatchConflictDetector.Report report = detector.detect(List.of(
                candidate("one", patch("src/A.java", "MODIFY", null, 1, 1)),
                candidate("two", patch("src/B.java", "MODIFY", null, 1, 1))));

        assertThat(report.safeToIntegrate()).isTrue();
        assertThat(report.conflicts()).isEmpty();
    }

    @Test
    void detectsCommonFilesAndIncompatibleHunksSeparately() {
        PatchConflictDetector.Report report = detector.detect(List.of(
                candidate("one", patch("src/A.java", "MODIFY", null, 10, 4)),
                candidate("two", patch("src/A.java", "MODIFY", null, 12, 3))));

        assertThat(report.safeToIntegrate()).isFalse();
        assertThat(report.conflicts()).extracting(PatchConflictDetector.Conflict::type)
                .containsExactly(PatchConflictDetector.Type.COMMON_FILE,
                        PatchConflictDetector.Type.INCOMPATIBLE_HUNK);
    }

    @Test
    void detectsDivergentRenamesAndDeleteVersusModification() {
        PatchConflictDetector.Report rename = detector.detect(List.of(
                candidate("one", patch("src/B.java", "RENAME", "src/A.java", 1, 1)),
                candidate("two", patch("src/C.java", "RENAME", "src/A.java", 1, 1))));
        PatchConflictDetector.Report deletion = detector.detect(List.of(
                candidate("one", patch("src/A.java", "DELETE", null, 1, 1)),
                candidate("two", patch("src/A.java", "MODIFY", null, 4, 1))));

        assertThat(rename.conflicts()).extracting(PatchConflictDetector.Conflict::type)
                .contains(PatchConflictDetector.Type.RENAME_COLLISION);
        assertThat(deletion.conflicts()).extracting(PatchConflictDetector.Conflict::type)
                .contains(PatchConflictDetector.Type.DELETE_COLLISION,
                        PatchConflictDetector.Type.COMMON_FILE);
    }

    private static PatchConflictDetector.Candidate candidate(
            String id, PatchProposalValidator.ValidatedPatch patch) {
        return new PatchConflictDetector.Candidate(id, patch);
    }

    private static PatchProposalValidator.ValidatedPatch patch(String path, String operation,
                                                                String previousPath, int oldStart, int oldCount) {
        String oldPath = previousPath == null ? path : previousPath;
        String content = "diff --git a/" + oldPath + " b/" + path + "\n"
                + "@@ -" + oldStart + ',' + oldCount + " +" + oldStart + ',' + oldCount + " @@\n"
                + " context\n";
        return new PatchProposalValidator.ValidatedPatch(content, PatchIntegrator.digestFor(content),
                content.getBytes(StandardCharsets.UTF_8).length,
                List.of(new PatchProposalValidator.FileChange(path, previousPath, operation)));
    }
}
