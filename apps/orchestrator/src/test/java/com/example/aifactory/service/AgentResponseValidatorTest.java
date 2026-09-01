package com.example.aifactory.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentResponseValidatorTest {
    private final AgentResponseValidator validator = new AgentResponseValidator(new ObjectMapper());

    @Test
    void acceptsImplementablePlan() {
        assertDoesNotThrow(() -> validator.requireImplementablePlan("{\"status\":\"IMPLEMENTABLE\",\"risk_level\":\"R1\"}"));
    }

    @Test
    void blocksNonImplementablePlan() {
        assertThrows(IllegalStateException.class,
                () -> validator.requireImplementablePlan("{\"status\":\"NEEDS_CLARIFICATION\",\"risk_level\":\"R1\"}"));
    }

    @Test
    void distinguishesContractValidityFromThePlannerBusinessDecision() {
        String valid = """
                {
                  "status": "NEEDS_CLARIFICATION",
                  "summary": "A decision is required",
                  "risk_level": "R1",
                  "impacted_files": [],
                  "domain_impacts": [],
                  "api_and_data_impacts": [],
                  "tests": [],
                  "security": [],
                  "performance": [],
                  "rollback_and_compatibility": [],
                  "assumptions": [],
                  "open_questions": ["Which behavior is expected?"],
                  "human_decisions": []
                }
                """;

        assertTrue(validator.hasValidPlannerContract(valid));
        assertFalse(validator.hasValidPlannerContract("{\"risk_level\":\"R1\"}"));
        assertFalse(validator.hasValidPlannerContract(valid.replace("NEEDS_CLARIFICATION", "UNKNOWN")));
        assertFalse(validator.hasValidPlannerContract(valid.replace("\"security\": [],", "")));
        assertFalse(validator.hasValidPlannerContract(valid.replace("\"tests\": []", "\"tests\": {}")));
    }

    @Test
    void rejectsReviewerRejection() {
        assertThrows(IllegalStateException.class,
                () -> validator.requireReviewAllowsApproval("{\"decision\":\"REJECT\"}"));
    }

    @Test
    void summarizesReviewerFindingsForSafeOperationalLogging() {
        AgentResponseValidator.ReviewSummary review = validator.summarizeReview("""
                {"decision":"REJECT","findings":[
                  {"file":"CustomerController.java","severity":"major","rule":"Missing access-control policy","fix":"Declare the endpoint policy"},
                  {"file":"CustomerControllerTest.java","severity":"minor","rule":"Missing edge-case coverage","fix":"Add tests"}
                ]}
                """);

        assertEquals("REJECT", review.decision());
        assertEquals("blocker=0, major=1, minor=1", review.findingCounts());
        assertEquals("Missing access-control policy", review.findings().getFirst().rule());
    }

    @Test
    void acceptsFencedReviewerJson() {
        assertDoesNotThrow(() -> validator.requireReviewAllowsApproval("```json\n{\"decision\":\"ACCEPT\"}\n```"));
    }

    @Test
    void blocksHighRiskPlanUntilPriorApprovalExists() {
        assertThrows(IllegalStateException.class,
                () -> validator.requireImplementablePlan("{\"status\":\"IMPLEMENTABLE\",\"risk_level\":\"R3\"}"));
    }

    @Test
    void blocksReviewerOutputWithBlockerFinding() {
        assertThrows(IllegalStateException.class, () -> validator.requireReviewAllowsApproval(
                "{\"decision\":\"ACCEPT\",\"findings\":[{\"severity\":\"blocker\"}]}"));
    }

    @Test
    void requiresTheTesterEvidenceContract() {
        assertDoesNotThrow(() -> validator.requireTesterReport("""
                {"coverage_gaps":[],"test_cases":[],"evidence":[],"unverified":[]}
                """));
        assertThrows(IllegalStateException.class,
                () -> validator.requireTesterReport("{\"coverage_gaps\":[]}"));
    }
}
