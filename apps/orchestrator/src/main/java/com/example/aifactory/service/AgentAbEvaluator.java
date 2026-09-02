package com.example.aifactory.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Produces a fail-closed A/B qualification from paired reference-suite observations. */
public final class AgentAbEvaluator {
    public Report evaluate(List<Observation> observations, Thresholds thresholds) {
        return evaluate(observations, thresholds, Variant.BASELINE, Variant.CANDIDATE);
    }

    public Report evaluate(List<Observation> observations, Thresholds thresholds,
                           Variant referenceVariant, Variant candidateVariant) {
        if (referenceVariant == null || candidateVariant == null || referenceVariant == candidateVariant) {
            throw new IllegalArgumentException("Two distinct evaluation variants are required");
        }
        Map<String, List<Observation>> byCase = observations.stream().collect(Collectors.groupingBy(Observation::caseId));
        Set<Variant> expectedVariants = Set.of(referenceVariant, candidateVariant);
        if (byCase.size() < thresholds.minimumCases()
                || byCase.values().stream().anyMatch(values -> !variants(values).equals(expectedVariants))) {
            return new Report("INCOMPLETE", byCase.size(), null, null,
                    List.of("paired_reference_suite_required"));
        }
        Metrics baseline = metrics(observations, referenceVariant);
        Metrics candidate = metrics(observations, candidateVariant);
        java.util.ArrayList<String> failures = new java.util.ArrayList<>();
        if (candidate.firstPatchSuccessRate() < baseline.firstPatchSuccessRate() - thresholds.maxQualityRegression())
            failures.add("first_patch_success_regression");
        if (candidate.testSuccessRate() < baseline.testSuccessRate() - thresholds.maxQualityRegression())
            failures.add("test_success_regression");
        if (candidate.humanAcceptanceRate() < baseline.humanAcceptanceRate() - thresholds.maxQualityRegression())
            failures.add("human_acceptance_regression");
        if (candidate.securityFailures() > 0) failures.add("security_regression");
        if (candidate.averageRepairs() > baseline.averageRepairs() + thresholds.maxRepairIncrease())
            failures.add("repair_regression");
        if (candidate.averageTokens() > baseline.averageTokens() * (1 + thresholds.maxResourceIncrease()))
            failures.add("token_regression");
        if (candidate.averageDurationMillis() > baseline.averageDurationMillis() * (1 + thresholds.maxResourceIncrease()))
            failures.add("duration_regression");
        if (candidate.averageCostMicros() > baseline.averageCostMicros() * (1 + thresholds.maxResourceIncrease()))
            failures.add("cost_regression");
        return new Report(failures.isEmpty() ? "QUALIFIED" : "REJECTED", byCase.size(), baseline, candidate,
                List.copyOf(failures));
    }

    public ComparisonReport compareAll(List<CampaignObservation> observations, Thresholds thresholds,
                                       Set<Variant> expectedVariants) {
        if (expectedVariants == null || expectedVariants.size() < 2) {
            throw new IllegalArgumentException("At least two explicit variants are required");
        }
        Map<String, List<CampaignObservation>> byCase = observations.stream()
                .collect(Collectors.groupingBy(value -> value.observation().caseId()));
        boolean invalidPairing = byCase.size() < thresholds.minimumCases() || byCase.values().stream().anyMatch(values ->
                values.size() != expectedVariants.size()
                || !values.stream().map(value -> value.observation().variant()).collect(Collectors.toSet())
                        .equals(expectedVariants)
                || values.stream().map(CampaignObservation::ticketId).distinct().count() != 1
                || values.stream().map(CampaignObservation::sourceCommit).distinct().count() != 1);
        if (invalidPairing) {
            return new ComparisonReport("INCOMPLETE", byCase.size(), Map.of(),
                    List.of("same_ticket_commit_and_variants_required"));
        }
        Map<Variant, Metrics> metrics = expectedVariants.stream().collect(Collectors.toUnmodifiableMap(
                variant -> variant, variant -> metrics(observations.stream()
                        .map(CampaignObservation::observation).toList(), variant)));
        return new ComparisonReport("COMPLETE", byCase.size(), metrics, List.of());
    }

    private static Set<Variant> variants(List<Observation> values) {
        return values.stream().map(Observation::variant).collect(Collectors.toSet());
    }

    private static Metrics metrics(List<Observation> all, Variant variant) {
        List<Observation> values = all.stream().filter(value -> value.variant() == variant).toList();
        int count = values.size();
        return new Metrics(rate(values, Observation::firstPatchSuccess),
                values.stream().mapToInt(Observation::repairs).average().orElse(0),
                rate(values, Observation::testsPassed), rate(values, Observation::humanAccepted),
                values.stream().mapToLong(Observation::tokens).average().orElse(0),
                values.stream().mapToLong(Observation::durationMillis).average().orElse(0),
                values.stream().mapToLong(Observation::costMicros).average().orElse(0),
                (int) values.stream().filter(Observation::securityFailure).count(), count);
    }

    private static double rate(List<Observation> values, java.util.function.Predicate<Observation> predicate) {
        return values.isEmpty() ? 0 : (double) values.stream().filter(predicate).count() / values.size();
    }

    public enum Variant { BASELINE, CANDIDATE, PIPELINE, AGENTIC_SIMPLE, HIERARCHICAL_SHADOW }

    public record Observation(String caseId, Variant variant, boolean firstPatchSuccess, int repairs,
                              boolean testsPassed, boolean humanAccepted, long tokens,
                              long durationMillis, long costMicros, boolean securityFailure) {
    }

    public record CampaignObservation(String ticketId, String sourceCommit, Observation observation) {
        public CampaignObservation {
            if (ticketId == null || ticketId.isBlank() || sourceCommit == null
                    || !sourceCommit.matches("[0-9a-f]{40}") || observation == null) {
                throw new IllegalArgumentException("Campaign observation requires ticket, commit and metrics");
            }
        }
    }

    public record Thresholds(int minimumCases, double maxQualityRegression, double maxRepairIncrease,
                             double maxResourceIncrease) {
    }

    public record Metrics(double firstPatchSuccessRate, double averageRepairs, double testSuccessRate,
                          double humanAcceptanceRate, double averageTokens, double averageDurationMillis,
                          double averageCostMicros, int securityFailures, int cases) {
    }

    public record Report(String verdict, int pairedCases, Metrics baseline, Metrics candidate,
                         List<String> failures) {
    }

    public record ComparisonReport(String status, int pairedCases, Map<Variant, Metrics> metrics,
                                   List<String> failures) {
    }
}
