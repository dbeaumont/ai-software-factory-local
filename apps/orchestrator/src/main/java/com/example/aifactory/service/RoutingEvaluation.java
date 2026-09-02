package com.example.aifactory.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic offline metrics for routing decisions and specialist selection. */
public final class RoutingEvaluation {
    public Report evaluate(List<Case> cases) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("Routing evaluation cases are required");
        }
        int correctPaths = 0;
        int expectedSpecialists = 0;
        int selectedSpecialists = 0;
        int relevantSelections = 0;
        for (Case sample : cases) {
            if (sample == null) throw new IllegalArgumentException("Routing evaluation case is required");
            if (sample.expectedPath() == sample.actualPath()) correctPaths++;
            Set<String> expected = new HashSet<>(sample.expectedSpecialists());
            Set<String> selected = new HashSet<>(sample.selectedSpecialists());
            expectedSpecialists += expected.size();
            selectedSpecialists += selected.size();
            selected.retainAll(expected);
            relevantSelections += selected.size();
        }
        return new Report(rate(correctPaths, cases.size()),
                rate(relevantSelections, selectedSpecialists),
                rate(relevantSelections, expectedSpecialists),
                cases.size(), expectedSpecialists, selectedSpecialists);
    }

    private static double rate(int numerator, int denominator) {
        return denominator == 0 ? 1.0 : (double) numerator / denominator;
    }

    public enum Path { SHORT_CODE_PATH, HIERARCHICAL_PATH, HUMAN_TRIAGE }

    public record Case(String caseId, Path expectedPath, Path actualPath,
                       Set<String> expectedSpecialists, Set<String> selectedSpecialists) {
        public Case {
            if (caseId == null || caseId.isBlank() || expectedPath == null || actualPath == null
                    || expectedSpecialists == null || selectedSpecialists == null) {
                throw new IllegalArgumentException("Routing evaluation case is incomplete");
            }
            expectedSpecialists = Set.copyOf(expectedSpecialists);
            selectedSpecialists = Set.copyOf(selectedSpecialists);
        }
    }

    public record Report(double pathAccuracy, double specialistPrecision, double specialistRecall,
                         int cases, int expectedSpecialists, int selectedSpecialists) { }
}
