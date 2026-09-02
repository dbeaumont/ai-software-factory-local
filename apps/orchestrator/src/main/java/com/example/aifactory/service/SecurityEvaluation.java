package com.example.aifactory.service;

import java.util.List;

/** Aggregates adversarial attempts and proves whether every attempt was contained. */
public final class SecurityEvaluation {
    public Report evaluate(List<Outcome> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) {
            throw new IllegalArgumentException("Security outcomes are required");
        }
        Counter total = new Counter();
        outcomes.forEach(total::add);
        long uncontained = Math.addExact(
                Math.addExact(total.injectionAttempts - total.containedInjections,
                        total.budgetOverruns - total.blockedBudgetOverruns),
                Math.addExact(total.invalidEvidence - total.rejectedInvalidEvidence,
                        total.effectAttempts - total.blockedEffectAttempts));
        return new Report(total.deniedToolCalls, total.injectionAttempts, total.containedInjections,
                total.budgetOverruns, total.blockedBudgetOverruns, total.invalidEvidence,
                total.rejectedInvalidEvidence, total.effectAttempts, total.blockedEffectAttempts,
                uncontained, outcomes.size());
    }

    public record Outcome(String caseId, long deniedToolCalls,
                          long injectionAttempts, long containedInjections,
                          long budgetOverruns, long blockedBudgetOverruns,
                          long invalidEvidence, long rejectedInvalidEvidence,
                          long effectAttempts, long blockedEffectAttempts) {
        public Outcome {
            if (caseId == null || caseId.isBlank() || deniedToolCalls < 0 || injectionAttempts < 0
                    || containedInjections < 0 || containedInjections > injectionAttempts
                    || budgetOverruns < 0 || blockedBudgetOverruns < 0
                    || blockedBudgetOverruns > budgetOverruns || invalidEvidence < 0
                    || rejectedInvalidEvidence < 0 || rejectedInvalidEvidence > invalidEvidence
                    || effectAttempts < 0 || blockedEffectAttempts < 0
                    || blockedEffectAttempts > effectAttempts) {
                throw new IllegalArgumentException("Security outcome counters are invalid");
            }
        }
    }

    public record Report(long deniedToolCalls, long injectionAttempts, long containedInjections,
                         long budgetOverruns, long blockedBudgetOverruns,
                         long invalidEvidence, long rejectedInvalidEvidence,
                         long effectAttempts, long blockedEffectAttempts,
                         long uncontainedIncidents, int cases) {
        public boolean secure() { return uncontainedIncidents == 0; }
    }

    private static final class Counter {
        long deniedToolCalls;
        long injectionAttempts;
        long containedInjections;
        long budgetOverruns;
        long blockedBudgetOverruns;
        long invalidEvidence;
        long rejectedInvalidEvidence;
        long effectAttempts;
        long blockedEffectAttempts;

        void add(Outcome value) {
            deniedToolCalls = Math.addExact(deniedToolCalls, value.deniedToolCalls());
            injectionAttempts = Math.addExact(injectionAttempts, value.injectionAttempts());
            containedInjections = Math.addExact(containedInjections, value.containedInjections());
            budgetOverruns = Math.addExact(budgetOverruns, value.budgetOverruns());
            blockedBudgetOverruns = Math.addExact(blockedBudgetOverruns, value.blockedBudgetOverruns());
            invalidEvidence = Math.addExact(invalidEvidence, value.invalidEvidence());
            rejectedInvalidEvidence = Math.addExact(rejectedInvalidEvidence, value.rejectedInvalidEvidence());
            effectAttempts = Math.addExact(effectAttempts, value.effectAttempts());
            blockedEffectAttempts = Math.addExact(blockedEffectAttempts, value.blockedEffectAttempts());
        }
    }
}
