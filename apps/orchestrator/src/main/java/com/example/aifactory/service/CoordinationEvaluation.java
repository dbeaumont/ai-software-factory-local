package com.example.aifactory.service;

import java.util.List;

/** Aggregates scope, collision-prevention and contradiction-detection campaign outcomes. */
public final class CoordinationEvaluation {
    public Report evaluate(List<Case> cases) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("Coordination evaluation cases are required");
        }
        long scopes = 0;
        long validScopes = 0;
        long collisionRisks = 0;
        long collisionsPrevented = 0;
        long expectedContradictions = 0;
        long detectedContradictions = 0;
        for (Case sample : cases) {
            if (sample == null) throw new IllegalArgumentException("Coordination evaluation case is required");
            scopes = Math.addExact(scopes, sample.scopeAssignments());
            validScopes = Math.addExact(validScopes, sample.validScopeAssignments());
            collisionRisks = Math.addExact(collisionRisks, sample.potentialCollisions());
            collisionsPrevented = Math.addExact(collisionsPrevented, sample.collisionsPrevented());
            expectedContradictions = Math.addExact(expectedContradictions, sample.expectedContradictions());
            detectedContradictions = Math.addExact(detectedContradictions, sample.detectedContradictions());
        }
        return new Report(rate(validScopes, scopes), rate(collisionsPrevented, collisionRisks),
                rate(detectedContradictions, expectedContradictions), cases.size(), scopes,
                collisionRisks, expectedContradictions);
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 1.0 : (double) numerator / denominator;
    }

    public record Case(String caseId, int scopeAssignments, int validScopeAssignments,
                       int potentialCollisions, int collisionsPrevented,
                       int expectedContradictions, int detectedContradictions) {
        public Case {
            if (caseId == null || caseId.isBlank() || scopeAssignments < 0 || validScopeAssignments < 0
                    || validScopeAssignments > scopeAssignments || potentialCollisions < 0
                    || collisionsPrevented < 0 || collisionsPrevented > potentialCollisions
                    || expectedContradictions < 0 || detectedContradictions < 0
                    || detectedContradictions > expectedContradictions) {
                throw new IllegalArgumentException("Coordination evaluation counters are invalid");
            }
        }
    }

    public record Report(double validScopeRate, double collisionPreventionRate,
                         double contradictionRecall, int cases, long scopeAssignments,
                         long potentialCollisions, long expectedContradictions) { }
}
