package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlannerResponseFormatTest {

    @Test
    @SuppressWarnings("unchecked")
    void requiresEveryPlannerFieldAndRejectsAdditionalProperties() {
        Map<String, Object> format = PlannerResponseFormat.planner();
        Map<String, Object> jsonSchema = (Map<String, Object>) format.get("json_schema");
        Map<String, Object> schema = (Map<String, Object>) jsonSchema.get("schema");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        List<String> required = (List<String>) schema.get("required");

        assertEquals("json_schema", format.get("type"));
        assertEquals(Boolean.TRUE, jsonSchema.get("strict"));
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
        assertEquals(properties.keySet(), Set.copyOf(required));
        assertTrue(required.containsAll(List.of("status", "risk_level", "impacted_files", "tests")));
    }

    @Test
    void appliesTheSchemaOnlyToPlannerRequests() {
        assertNotNull(PlannerResponseFormat.forPrompt("planner"));
        assertNull(PlannerResponseFormat.forPrompt("developer"));

        Map<String, Object> plannerBody = LlmGatewayClient.requestBody(
                "model", "system", "user", 1200, PlannerResponseFormat.forPrompt("planner"));
        Map<String, Object> developerBody = LlmGatewayClient.requestBody(
                "model", "system", "user", 1200, PlannerResponseFormat.forPrompt("developer"));

        assertTrue(plannerBody.containsKey("response_format"));
        assertFalse(developerBody.containsKey("response_format"));
    }
}
