package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.aifactory.service.CrossPerimeterContradictionDetector.Perimeter.ARCHITECTURE;
import static com.example.aifactory.service.CrossPerimeterContradictionDetector.Perimeter.CODE;
import static com.example.aifactory.service.CrossPerimeterContradictionDetector.Perimeter.SECURITY;
import static com.example.aifactory.service.CrossPerimeterContradictionDetector.Perimeter.TESTS;
import static com.example.aifactory.service.CrossPerimeterContradictionDetector.Dimension.FACT;
import static com.example.aifactory.service.CrossPerimeterContradictionDetector.Dimension.RECOMMENDATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossPerimeterContradictionDetectorTest {
    private final CrossPerimeterContradictionDetector detector = new CrossPerimeterContradictionDetector();

    @Test
    void detectsContradictionsAcrossAllFourSpecialistPerimeters() {
        var architecture = result("architecture-1", ARCHITECTURE, "architecture-agent",
                assertion("api.orders.compatibility", FACT, "compatible"),
                assertion("release.ready", RECOMMENDATION, "yes"));
        var code = result("code-1", CODE, "code-agent",
                assertion("api.orders.compatibility", FACT, "breaking"),
                assertion("release.ready", RECOMMENDATION, "yes"));
        var tests = result("tests-1", TESTS, "test-evidence",
                assertion("release.ready", RECOMMENDATION, "no"));
        var security = result("security-1", SECURITY, "security-agent",
                assertion("release.ready", RECOMMENDATION, "no"));

        List<CrossPerimeterContradictionDetector.Candidate> detected = detector.detect(
                new CrossPerimeterContradictionDetector.Request("task-1", "attempt-1",
                        List.of(tests, architecture, security, code)));

        assertThat(detected).extracting(CrossPerimeterContradictionDetector.Candidate::subject)
                .containsExactly("api.orders.compatibility", "release.ready");
        assertThat(detected.getFirst().sources())
                .extracting(CrossPerimeterContradictionDetector.Source::perimeter)
                .containsExactly(ARCHITECTURE, CODE);
        assertThat(detected.get(1).sources())
                .extracting(CrossPerimeterContradictionDetector.Source::perimeter)
                .containsExactly(ARCHITECTURE, CODE, SECURITY, TESTS);
    }

    @Test
    void producesStableCandidatesRegardlessOfResultAndAssertionOrder() {
        var architecture = result("architecture-1", ARCHITECTURE, "architecture-agent",
                assertion("release.ready", RECOMMENDATION, "YES"));
        var tests = result("tests-1", TESTS, "test-evidence",
                assertion("release.ready", RECOMMENDATION, "NO"));

        var forward = detector.detect(request(List.of(architecture, tests)));
        var reverse = detector.detect(request(List.of(tests, architecture)));

        assertThat(forward).isEqualTo(reverse);
        assertThat(forward.getFirst().contradictionId()).startsWith("contradiction-").hasSize(38);
    }

    @Test
    void ignoresAgreementAndDifferencesLimitedToOnePerimeter() {
        var architectureA = result("architecture-1", ARCHITECTURE, "architecture-agent",
                assertion("release.ready", RECOMMENDATION, "YES"),
                assertion("internal.choice", FACT, "A"));
        var architectureB = result("architecture-2", ARCHITECTURE, "impact-analysis",
                assertion("internal.choice", FACT, "B"));
        var security = result("security-1", SECURITY, "security-agent",
                assertion("release.ready", RECOMMENDATION, "yes"));

        assertThat(detector.detect(request(List.of(architectureA, architectureB, security)))).isEmpty();
    }

    @Test
    void rejectsCrossAttemptAndDuplicateResults() {
        var architecture = result("same", ARCHITECTURE, "architecture-agent",
                assertion("release.ready", RECOMMENDATION, "YES"));
        var duplicate = result("same", TESTS, "test-evidence",
                assertion("release.ready", RECOMMENDATION, "NO"));
        var wrongAttempt = new CrossPerimeterContradictionDetector.SpecialistResult(
                "tests-2", "task-1", "attempt-2", TESTS, "test-evidence", "b".repeat(64),
                List.of("evidence://task-1/tests"),
                List.of(assertion("release.ready", RECOMMENDATION, "NO")));

        assertThatThrownBy(() -> detector.detect(request(List.of(architecture, duplicate))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unique");
        assertThatThrownBy(() -> detector.detect(request(List.of(architecture, wrongAttempt))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("workflow attempt");
    }

    private static CrossPerimeterContradictionDetector.Request request(
            List<CrossPerimeterContradictionDetector.SpecialistResult> results) {
        return new CrossPerimeterContradictionDetector.Request("task-1", "attempt-1", results);
    }

    private static CrossPerimeterContradictionDetector.SpecialistResult result(
            String id, CrossPerimeterContradictionDetector.Perimeter perimeter, String role,
            CrossPerimeterContradictionDetector.Assertion... assertions) {
        return new CrossPerimeterContradictionDetector.SpecialistResult(id, "task-1", "attempt-1", perimeter,
                role, "a".repeat(64), List.of("evidence://task-1/" + id), List.of(assertions));
    }

    private static CrossPerimeterContradictionDetector.Assertion assertion(
            String subject, CrossPerimeterContradictionDetector.Dimension dimension, String conclusion) {
        return new CrossPerimeterContradictionDetector.Assertion(subject, dimension, conclusion);
    }
}
