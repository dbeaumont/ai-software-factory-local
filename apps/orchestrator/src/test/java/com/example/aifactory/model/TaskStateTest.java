package com.example.aifactory.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskStateTest {
    @Test
    void distinguishesModelReviewFromHumanApprovalInEvaluationMetrics() {
        TaskState state = new TaskState("task-180", "AF-0180", new TaskRequest(
                "http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD));
        state.reviewAccepted = true;

        assertEquals(true, state.view().evaluationMetrics().get("review_accepted"));
        assertEquals(false, state.view().evaluationMetrics().get("human_accepted"));

        state.humanApproved = true;
        assertEquals(true, state.view().evaluationMetrics().get("human_accepted"));
    }
}
