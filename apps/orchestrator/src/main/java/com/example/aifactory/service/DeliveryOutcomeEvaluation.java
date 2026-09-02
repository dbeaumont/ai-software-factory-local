package com.example.aifactory.service;

import java.util.List;

/** Quality funnel from first patch through independent review and human acceptance. */
public final class DeliveryOutcomeEvaluation {
    public Report evaluate(List<Outcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            throw new IllegalArgumentException("Delivery outcomes are required");
        }
        return new Report(rate(outcomes, Outcome::firstPatchSuccess),
                outcomes.stream().mapToInt(Outcome::repairs).average().orElse(0),
                rate(outcomes, Outcome::testsPassed), rate(outcomes, Outcome::reviewAccepted),
                rate(outcomes, Outcome::humanAccepted), outcomes.size());
    }

    private static double rate(List<Outcome> values, java.util.function.Predicate<Outcome> predicate) {
        return (double) values.stream().filter(predicate).count() / values.size();
    }

    public record Outcome(String caseId, boolean firstPatchSuccess, int repairs,
                          boolean testsPassed, boolean reviewAccepted, boolean humanAccepted) {
        public Outcome {
            if (caseId == null || caseId.isBlank() || repairs < 0) {
                throw new IllegalArgumentException("Delivery outcome is invalid");
            }
        }
    }

    public record Report(double firstPatchSuccessRate, double averageRepairs,
                         double testSuccessRate, double reviewAcceptanceRate,
                         double humanAcceptanceRate, int cases) { }
}
