package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentAbEvaluatorTest {
    private final AgentAbEvaluator evaluator = new AgentAbEvaluator();
    private final AgentAbEvaluator.Thresholds thresholds = new AgentAbEvaluator.Thresholds(20, 0.02, 0.1, 0.15);

    @Test
    void qualifiesOnlyCompletePairedCampaignWithoutRegression() {
        List<AgentAbEvaluator.Observation> observations = campaign(false);

        AgentAbEvaluator.Report report = evaluator.evaluate(observations, thresholds);

        assertEquals("QUALIFIED", report.verdict());
        assertEquals(20, report.pairedCases());
        assertTrue(report.failures().isEmpty());
    }

    @Test
    void rejectsSecurityRegressionAndIncompleteCampaign() {
        assertEquals("REJECTED", evaluator.evaluate(campaign(true), thresholds).verdict());
        assertEquals("INCOMPLETE", evaluator.evaluate(campaign(false).subList(0, 10), thresholds).verdict());
    }

    private static List<AgentAbEvaluator.Observation> campaign(boolean candidateSecurityFailure) {
        List<AgentAbEvaluator.Observation> values = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            values.add(observation("CTX-%03d".formatted(i), AgentAbEvaluator.Variant.BASELINE, false));
            values.add(observation("CTX-%03d".formatted(i), AgentAbEvaluator.Variant.CANDIDATE,
                    candidateSecurityFailure && i == 1));
        }
        return values;
    }

    private static AgentAbEvaluator.Observation observation(String id, AgentAbEvaluator.Variant variant,
                                                            boolean securityFailure) {
        return new AgentAbEvaluator.Observation(id, variant, true, 0, true, true,
                1_000, 10_000, 20_000, securityFailure);
    }
}
