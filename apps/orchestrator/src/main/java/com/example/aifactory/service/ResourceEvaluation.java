package com.example.aifactory.service;

import java.util.List;

/** Aggregates complete technical, financial and human resource consumption. */
public final class ResourceEvaluation {
    public Report evaluate(List<Usage> usages) {
        if (usages == null || usages.isEmpty()) {
            throw new IllegalArgumentException("Resource usage samples are required");
        }
        long tokens = 0;
        long costMicros = 0;
        long durationMillis = 0;
        long sandboxComputeMillis = 0;
        long humanMillis = 0;
        for (Usage usage : usages) {
            if (usage == null) throw new IllegalArgumentException("Resource usage sample is required");
            tokens = Math.addExact(tokens, usage.tokens());
            costMicros = Math.addExact(costMicros, usage.costMicros());
            durationMillis = Math.addExact(durationMillis, usage.durationMillis());
            sandboxComputeMillis = Math.addExact(sandboxComputeMillis, usage.sandboxComputeMillis());
            humanMillis = Math.addExact(humanMillis, usage.humanMillis());
        }
        List<Long> orderedDurations = usages.stream().map(Usage::durationMillis).sorted().toList();
        int p95Index = Math.max(0, (int) Math.ceil(orderedDurations.size() * 0.95) - 1);
        return new Report(tokens, costMicros, durationMillis, sandboxComputeMillis, humanMillis,
                average(tokens, usages.size()), average(costMicros, usages.size()),
                average(durationMillis, usages.size()), average(sandboxComputeMillis, usages.size()),
                average(humanMillis, usages.size()), orderedDurations.get(p95Index), usages.size());
    }

    private static double average(long value, int count) {
        return (double) value / count;
    }

    public record Usage(String caseId, long tokens, long costMicros, long durationMillis,
                        long sandboxComputeMillis, long humanMillis) {
        public Usage {
            if (caseId == null || caseId.isBlank() || tokens < 0 || costMicros < 0
                    || durationMillis < 0 || sandboxComputeMillis < 0 || humanMillis < 0) {
                throw new IllegalArgumentException("Resource usage sample is invalid");
            }
        }
    }

    public record Report(long totalTokens, long totalCostMicros, long totalDurationMillis,
                         long totalSandboxComputeMillis, long totalHumanMillis,
                         double averageTokens, double averageCostMicros, double averageDurationMillis,
                         double averageSandboxComputeMillis, double averageHumanMillis,
                         long p95DurationMillis, int cases) { }
}
