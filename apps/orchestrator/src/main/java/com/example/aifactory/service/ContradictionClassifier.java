package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.util.Map;

/** Maps the closed assertion taxonomy to the five contradiction classes accepted by consolidation. */
@Component
public final class ContradictionClassifier {
    private static final Map<CrossPerimeterContradictionDetector.Dimension, Classification> CLASSIFICATIONS = Map.of(
            CrossPerimeterContradictionDetector.Dimension.FACT, Classification.FACTUAL,
            CrossPerimeterContradictionDetector.Dimension.SCOPE, Classification.INCOMPATIBLE_SCOPE,
            CrossPerimeterContradictionDetector.Dimension.RISK, Classification.RISK,
            CrossPerimeterContradictionDetector.Dimension.TEST_COVERAGE, Classification.MISSING_TEST,
            CrossPerimeterContradictionDetector.Dimension.RECOMMENDATION,
            Classification.DIVERGENT_RECOMMENDATION);

    public ClassifiedCandidate classify(CrossPerimeterContradictionDetector.Candidate candidate) {
        if (candidate == null || candidate.dimension() == null || candidate.sources() == null
                || candidate.sources().size() < 2) {
            throw new IllegalArgumentException("Contradiction candidate is invalid");
        }
        Classification classification = CLASSIFICATIONS.get(candidate.dimension());
        if (classification == null) throw new IllegalArgumentException("Contradiction dimension is unsupported");
        return new ClassifiedCandidate(candidate, classification, classification.schemaType);
    }

    public record ClassifiedCandidate(CrossPerimeterContradictionDetector.Candidate candidate,
                                      Classification classification, String schemaType) {}

    public enum Classification {
        FACTUAL("FACT"),
        INCOMPATIBLE_SCOPE("SCOPE"),
        RISK("RISK"),
        MISSING_TEST("TEST"),
        DIVERGENT_RECOMMENDATION("RECOMMENDATION");

        private final String schemaType;

        Classification(String schemaType) {
            this.schemaType = schemaType;
        }
    }
}
