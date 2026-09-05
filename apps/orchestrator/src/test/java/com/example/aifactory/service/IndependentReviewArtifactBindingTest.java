package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndependentReviewArtifactBindingTest {
    @Test
    void requiresEveryProducedPipelineDigest() {
        Map<String, PipelineStepContracts.ArtifactReference> artifacts = Map.of(
                "plan", artifact("a"), "patch", artifact("b"), "tests", artifact("c"),
                "quality", artifact("d"), "security", artifact("e"));
        IndependentReviewBundle bundle = bundle(Map.of(
                "plan", "a".repeat(64), "patch", "b".repeat(64), "tests", "c".repeat(64),
                "quality", "d".repeat(64), "security", "e".repeat(64)));

        assertThatCode(() -> bundle.requireProductionArtifactBinding(artifacts)).doesNotThrowAnyException();
        assertThatThrownBy(() -> bundle.requireProductionArtifactBinding(Map.of(
                "plan", artifact("f"), "patch", artifact("b"), "tests", artifact("c"),
                "quality", artifact("d"), "security", artifact("e"))))
                .isInstanceOf(SecurityException.class);
    }

    private static IndependentReviewBundle bundle(Map<String, String> digests) {
        return new IndependentReviewBundle("task-1", "pipeline-1", "1".repeat(40),
                new IndependentReviewBundle.ConsolidatedPatch("patch-1", "evidence://task-1/patch",
                        "b".repeat(64), List.of("src/App.java")),
                new IndependentReviewBundle.FinalManifest("2".repeat(64),
                        "evidence://task-1/manifest", "3".repeat(64)),
                List.of(new IndependentReviewBundle.ResultReference("result-1", "code-agent",
                        "evidence://task-1/result", "4".repeat(64))), List.of(), digests);
    }

    private static PipelineStepContracts.ArtifactReference artifact(String character) {
        return new PipelineStepContracts.ArtifactReference("evidence://task-1/" + character,
                character.repeat(64), 1, "COMPLETE", "PASSED");
    }
}
