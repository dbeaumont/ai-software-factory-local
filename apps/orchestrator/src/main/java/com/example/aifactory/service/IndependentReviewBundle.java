package com.example.aifactory.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Immutable, workflow-owned inputs for the final independent review. */
public record IndependentReviewBundle(String taskId, String attemptId, String sourceCommit,
                                      ConsolidatedPatch consolidatedPatch, FinalManifest finalManifest,
                                      List<ResultReference> reviewedResults,
                                      List<ContradictionReference> contradictions) {
    private static final Set<String> RESULT_ROLES = Set.of(
            "supervisor", "architecture-agent", "code-agent", "developer", "patch-repair",
            "test-agent", "test-design", "test-evidence", "security-agent", "threat-model",
            "security-findings");

    public IndependentReviewBundle {
        if (!validId(taskId) || !validId(attemptId) || sourceCommit == null
                || !sourceCommit.matches("[0-9a-f]{40}") || consolidatedPatch == null
                || finalManifest == null || reviewedResults == null || reviewedResults.isEmpty()
                || contradictions == null) {
            throw new IllegalArgumentException("Independent review bundle is incomplete");
        }
        reviewedResults = List.copyOf(reviewedResults);
        contradictions = List.copyOf(contradictions);
        requireUnique(reviewedResults.stream().map(ResultReference::resultId).toList(), "result");
        requireUnique(contradictions.stream().map(ContradictionReference::contradictionId).toList(),
                "contradiction");
    }

    public boolean boundTo(String expectedTaskId, String expectedAttemptId, String expectedSourceCommit) {
        return taskId.equals(expectedTaskId) && attemptId.equals(expectedAttemptId)
                && sourceCommit.equals(expectedSourceCommit);
    }

    public Set<String> referenceIds() {
        HashSet<String> references = new HashSet<>();
        references.add(finalManifest.manifestId());
        reviewedResults.forEach(result -> references.add(result.resultId()));
        contradictions.forEach(contradiction -> references.add(contradiction.contradictionId()));
        return Set.copyOf(references);
    }

    public record ConsolidatedPatch(String patchId, String uri, String digest, List<String> changedFiles) {
        public ConsolidatedPatch {
            if (!validId(patchId) || !validEvidence(uri, digest) || changedFiles == null
                    || changedFiles.isEmpty() || changedFiles.stream().anyMatch(IndependentReviewBundle::unsafePath)) {
                throw new IllegalArgumentException("Consolidated patch reference is invalid");
            }
            changedFiles = List.copyOf(changedFiles);
            requireUnique(changedFiles, "changed file");
        }
    }

    public record FinalManifest(String manifestId, String uri, String digest) {
        public FinalManifest {
            if (manifestId == null || !manifestId.matches("[0-9a-f]{64}") || !validEvidence(uri, digest)) {
                throw new IllegalArgumentException("Final manifest reference is invalid");
            }
        }
    }

    public record ResultReference(String resultId, String role, String uri, String digest) {
        public ResultReference {
            if (!validId(resultId) || !RESULT_ROLES.contains(role) || !validEvidence(uri, digest)) {
                throw new IllegalArgumentException("Reviewed result reference is invalid");
            }
        }
    }

    public record ContradictionReference(String contradictionId, String status, String uri, String digest) {
        public ContradictionReference {
            if (!validId(contradictionId) || !Set.of("OPEN", "RESOLVED").contains(status)
                    || !validEvidence(uri, digest)) {
                throw new IllegalArgumentException("Contradiction reference is invalid");
            }
        }
    }

    private static boolean validId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");
    }

    private static boolean validEvidence(String uri, String digest) {
        return uri != null && uri.startsWith("evidence://")
                && digest != null && digest.matches("[0-9a-f]{64}");
    }

    private static boolean unsafePath(String path) {
        return path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                || List.of(path.split("/")).contains("..");
    }

    private static void requireUnique(List<String> values, String type) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException("Duplicate independent review " + type);
        }
    }
}
