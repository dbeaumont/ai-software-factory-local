package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicContradictionResolverTest {
    private final DecisionAuthorityPolicy authorities = new DecisionAuthorityPolicy();
    private final DeterministicContradictionResolver resolver = new DeterministicContradictionResolver(authorities);

    @Test
    void resolvesOnlyAnExplicitlyCoveredClassificationAndAuthorityPair() {
        var contradiction = contradiction(ContradictionClassifier.Classification.FACTUAL);

        DeterministicContradictionResolver.Result result = resolver.resolve(contradiction,
                List.of(claim("evidence", DecisionAuthorityPolicy.Authority.VERIFIED_EVIDENCE,
                        DecisionAuthorityPolicy.Verdict.DENY)));

        assertThat(resolver.policyId()).isEqualTo("contradiction-resolution-v1");
        assertThat(result.outcome()).isEqualTo(DeterministicContradictionResolver.Outcome.RESOLVED);
        assertThat(result.verdict()).isEqualTo(DecisionAuthorityPolicy.Verdict.DENY);
        assertThat(result.ruleId()).isEqualTo("factual.verified_evidence_wins");
    }

    @Test
    void leavesMissingTestsAndDivergentRecommendationsOpen() {
        for (ContradictionClassifier.Classification classification : List.of(
                ContradictionClassifier.Classification.MISSING_TEST,
                ContradictionClassifier.Classification.DIVERGENT_RECOMMENDATION)) {
            DeterministicContradictionResolver.Result result = resolver.resolve(contradiction(classification),
                    List.of(claim("gate", DecisionAuthorityPolicy.Authority.DETERMINISTIC_GATE,
                            DecisionAuthorityPolicy.Verdict.DENY)));

            assertThat(result.outcome()).isEqualTo(DeterministicContradictionResolver.Outcome.OPEN);
            assertThat(result.ruleId()).isNull();
        }
    }

    @Test
    void doesNotTreatSpecialistConsensusOrSupervisorAsDeterministicRules() {
        var contradiction = contradiction(ContradictionClassifier.Classification.FACTUAL);

        for (DecisionAuthorityPolicy.Authority authority : List.of(
                DecisionAuthorityPolicy.Authority.SPECIALIST_CONSENSUS,
                DecisionAuthorityPolicy.Authority.SUPERVISOR)) {
            DeterministicContradictionResolver.Result result = resolver.resolve(contradiction,
                    List.of(claim("claim-" + authority.name().toLowerCase(), authority,
                            DecisionAuthorityPolicy.Verdict.ALLOW)));
            assertThat(result.outcome()).isEqualTo(DeterministicContradictionResolver.Outcome.OPEN);
        }
    }

    @Test
    void escalatesAConflictAtTheControllingAuthority() {
        var contradiction = contradiction(ContradictionClassifier.Classification.RISK);

        DeterministicContradictionResolver.Result result = resolver.resolve(contradiction, List.of(
                claim("policy-a", DecisionAuthorityPolicy.Authority.POLICY, DecisionAuthorityPolicy.Verdict.ALLOW),
                claim("policy-b", DecisionAuthorityPolicy.Authority.POLICY, DecisionAuthorityPolicy.Verdict.DENY)));

        assertThat(result.outcome()).isEqualTo(DeterministicContradictionResolver.Outcome.ESCALATE);
        assertThat(result.verdict()).isNull();
        assertThat(result.ruleId()).isNull();
    }

    private static ContradictionClassifier.ClassifiedCandidate contradiction(
            ContradictionClassifier.Classification classification) {
        var candidate = new CrossPerimeterContradictionDetector.Candidate("contradiction-1", "task-1", "attempt-1",
                "shared.subject", CrossPerimeterContradictionDetector.Dimension.FACT, List.of(
                source("architecture-1", CrossPerimeterContradictionDetector.Perimeter.ARCHITECTURE, "A"),
                source("code-1", CrossPerimeterContradictionDetector.Perimeter.CODE, "B")));
        return new ContradictionClassifier.ClassifiedCandidate(candidate, classification,
                classification == ContradictionClassifier.Classification.FACTUAL ? "FACT" : classification.name());
    }

    private static CrossPerimeterContradictionDetector.Source source(
            String id, CrossPerimeterContradictionDetector.Perimeter perimeter, String conclusion) {
        return new CrossPerimeterContradictionDetector.Source(id, perimeter, perimeter.name().toLowerCase(),
                conclusion, "a".repeat(64), List.of("evidence://task-1/" + id));
    }

    private static DecisionAuthorityPolicy.Claim claim(String id, DecisionAuthorityPolicy.Authority authority,
                                                       DecisionAuthorityPolicy.Verdict verdict) {
        return new DecisionAuthorityPolicy.Claim(id, authority, verdict, "b".repeat(64));
    }
}
