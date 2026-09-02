package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContradictionClassifierTest {
    private final CrossPerimeterContradictionDetector detector = new CrossPerimeterContradictionDetector();
    private final ContradictionClassifier classifier = new ContradictionClassifier();

    @Test
    void classifiesTheFiveClosedContradictionKinds() {
        assertClassification(CrossPerimeterContradictionDetector.Dimension.FACT,
                ContradictionClassifier.Classification.FACTUAL, "FACT");
        assertClassification(CrossPerimeterContradictionDetector.Dimension.SCOPE,
                ContradictionClassifier.Classification.INCOMPATIBLE_SCOPE, "SCOPE");
        assertClassification(CrossPerimeterContradictionDetector.Dimension.RISK,
                ContradictionClassifier.Classification.RISK, "RISK");
        assertClassification(CrossPerimeterContradictionDetector.Dimension.TEST_COVERAGE,
                ContradictionClassifier.Classification.MISSING_TEST, "TEST");
        assertClassification(CrossPerimeterContradictionDetector.Dimension.RECOMMENDATION,
                ContradictionClassifier.Classification.DIVERGENT_RECOMMENDATION, "RECOMMENDATION");
    }

    private void assertClassification(CrossPerimeterContradictionDetector.Dimension dimension,
                                      ContradictionClassifier.Classification expected, String schemaType) {
        var architecture = result("architecture-1", CrossPerimeterContradictionDetector.Perimeter.ARCHITECTURE,
                dimension, "A");
        var code = result("code-1", CrossPerimeterContradictionDetector.Perimeter.CODE, dimension, "B");
        var candidate = detector.detect(new CrossPerimeterContradictionDetector.Request(
                "task-1", "attempt-1", List.of(architecture, code))).getFirst();

        ContradictionClassifier.ClassifiedCandidate classified = classifier.classify(candidate);

        assertThat(classified.classification()).isEqualTo(expected);
        assertThat(classified.schemaType()).isEqualTo(schemaType);
        assertThat(classified.candidate()).isEqualTo(candidate);
    }

    private static CrossPerimeterContradictionDetector.SpecialistResult result(
            String id, CrossPerimeterContradictionDetector.Perimeter perimeter,
            CrossPerimeterContradictionDetector.Dimension dimension, String conclusion) {
        return new CrossPerimeterContradictionDetector.SpecialistResult(id, "task-1", "attempt-1", perimeter,
                perimeter.name().toLowerCase(), "a".repeat(64), List.of("evidence://task-1/" + id),
                List.of(new CrossPerimeterContradictionDetector.Assertion("shared.subject", dimension, conclusion)));
    }
}
