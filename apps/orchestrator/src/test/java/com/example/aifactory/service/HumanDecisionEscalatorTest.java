package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HumanDecisionEscalatorTest {
    private final HumanDecisionEscalator escalator = new HumanDecisionEscalator();

    @Test
    void createsAStableDigestBoundRequestForEachOwnedOpenChoice() {
        for (Case value : List.of(
                new Case(ContradictionClassifier.Classification.DIVERGENT_RECOMMENDATION,
                        HumanDecisionEscalator.DecisionDomain.PRODUCT),
                new Case(ContradictionClassifier.Classification.INCOMPATIBLE_SCOPE,
                        HumanDecisionEscalator.DecisionDomain.ARCHITECTURE),
                new Case(ContradictionClassifier.Classification.RISK,
                        HumanDecisionEscalator.DecisionDomain.SECURITY),
                new Case(ContradictionClassifier.Classification.FACTUAL,
                        HumanDecisionEscalator.DecisionDomain.DATA))) {
            HumanDecisionEscalator.Escalation decision = escalator.escalate(contradiction(value.classification),
                    open(), value.domain, "d".repeat(64), "Quel choix retenir ?", options());

            assertThat(decision.workflowRequest().objectDigest()).isEqualTo("d".repeat(64));
            assertThat(decision.workflowRequest().requiredApproverRoles()).containsExactly(value.domain.name());
            assertThat(decision.workflowRequest().allowedDecisions()).containsExactlyInAnyOrder("OPTION_A", "OPTION_B");
            assertThat(decision.evidenceUris()).containsExactly("evidence://task-1/a", "evidence://task-1/b");
            assertThat(decision.requestId()).startsWith("decision-").hasSize(33);
        }
    }

    @Test
    void rejectsResolvedContradictionsWrongOwnersAndAmbiguousOptions() {
        var risk = contradiction(ContradictionClassifier.Classification.RISK);
        assertThatThrownBy(() -> escalator.escalate(risk, resolved(),
                HumanDecisionEscalator.DecisionDomain.SECURITY, "d".repeat(64), "Question", options()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unresolved");
        assertThatThrownBy(() -> escalator.escalate(risk, open(),
                HumanDecisionEscalator.DecisionDomain.PRODUCT, "d".repeat(64), "Question", options()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not own");
        assertThatThrownBy(() -> escalator.escalate(risk, open(),
                HumanDecisionEscalator.DecisionDomain.SECURITY, "d".repeat(64), "Question",
                List.of(options().getFirst(), options().getFirst())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unique");
    }

    private static List<HumanDecisionEscalator.Option> options() {
        return List.of(new HumanDecisionEscalator.Option("OPTION_B", "Option B", "Second choice", false),
                new HumanDecisionEscalator.Option("OPTION_A", "Option A", "First choice", true));
    }

    private static ContradictionClassifier.ClassifiedCandidate contradiction(
            ContradictionClassifier.Classification classification) {
        var a = new CrossPerimeterContradictionDetector.Source("a",
                CrossPerimeterContradictionDetector.Perimeter.ARCHITECTURE, "architecture-agent", "A",
                "a".repeat(64), List.of("evidence://task-1/b", "evidence://task-1/a"));
        var b = new CrossPerimeterContradictionDetector.Source("b",
                CrossPerimeterContradictionDetector.Perimeter.SECURITY, "security-agent", "B",
                "b".repeat(64), List.of("evidence://task-1/b"));
        var candidate = new CrossPerimeterContradictionDetector.Candidate("contradiction-1", "task-1",
                "attempt-1", "choice", dimension(classification), List.of(a, b));
        return new ContradictionClassifier.ClassifiedCandidate(candidate, classification, "TYPE");
    }

    private static CrossPerimeterContradictionDetector.Dimension dimension(
            ContradictionClassifier.Classification classification) {
        return switch (classification) {
            case FACTUAL -> CrossPerimeterContradictionDetector.Dimension.FACT;
            case INCOMPATIBLE_SCOPE -> CrossPerimeterContradictionDetector.Dimension.SCOPE;
            case RISK -> CrossPerimeterContradictionDetector.Dimension.RISK;
            case MISSING_TEST -> CrossPerimeterContradictionDetector.Dimension.TEST_COVERAGE;
            case DIVERGENT_RECOMMENDATION -> CrossPerimeterContradictionDetector.Dimension.RECOMMENDATION;
        };
    }

    private static DeterministicContradictionResolver.Result open() {
        return new DeterministicContradictionResolver.Result(DeterministicContradictionResolver.Outcome.OPEN,
                null, null, DecisionAuthorityPolicy.Authority.SUPERVISOR, List.of("supervisor"), "open");
    }

    private static DeterministicContradictionResolver.Result resolved() {
        return new DeterministicContradictionResolver.Result(DeterministicContradictionResolver.Outcome.RESOLVED,
                DecisionAuthorityPolicy.Verdict.ALLOW, "rule", DecisionAuthorityPolicy.Authority.POLICY,
                List.of("policy"), "resolved");
    }

    private record Case(ContradictionClassifier.Classification classification,
                        HumanDecisionEscalator.DecisionDomain domain) {}
}
