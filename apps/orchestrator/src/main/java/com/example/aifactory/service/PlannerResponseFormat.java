package com.example.aifactory.service;

import java.util.List;
import java.util.Map;

/** Strict OpenAI Chat Completions response format for the Planner contract. */
final class PlannerResponseFormat {
    private static final Map<String, Object> STRING = Map.of("type", "string");
    private static final Map<String, Object> STRING_ARRAY = Map.of("type", "array", "items", STRING);

    private PlannerResponseFormat() {
    }

    static Map<String, Object> forPrompt(String promptName) {
        return "planner".equals(promptName) ? planner() : null;
    }

    static Map<String, Object> planner() {
        Map<String, Object> impactedFile = object(
                Map.of(
                        "path", STRING,
                        "layer", STRING,
                        "change", STRING,
                        "evidence", STRING_ARRAY),
                List.of("path", "layer", "change", "evidence"));
        Map<String, Object> test = object(
                Map.of("name", STRING, "layer", STRING, "intent", STRING),
                List.of("name", "layer", "intent"));
        Map<String, Object> properties = Map.ofEntries(
                Map.entry("status", Map.of("type", "string", "enum",
                        List.of("IMPLEMENTABLE", "NEEDS_CLARIFICATION", "OUT_OF_SCOPE", "BLOCKED"))),
                Map.entry("summary", STRING),
                Map.entry("risk_level", Map.of("type", "string", "enum", List.of("R0", "R1", "R2", "R3", "R4"))),
                Map.entry("impacted_files", Map.of("type", "array", "items", impactedFile)),
                Map.entry("domain_impacts", STRING_ARRAY),
                Map.entry("api_and_data_impacts", STRING_ARRAY),
                Map.entry("tests", Map.of("type", "array", "items", test)),
                Map.entry("security", STRING_ARRAY),
                Map.entry("performance", STRING_ARRAY),
                Map.entry("rollback_and_compatibility", STRING_ARRAY),
                Map.entry("assumptions", STRING_ARRAY),
                Map.entry("open_questions", STRING_ARRAY),
                Map.entry("human_decisions", STRING_ARRAY));
        Map<String, Object> schema = object(properties, List.copyOf(properties.keySet()));
        return Map.of(
                "type", "json_schema",
                "json_schema", Map.of(
                        "name", "planner_response_v1",
                        "strict", true,
                        "schema", schema));
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type", "object",
                "properties", properties,
                "required", required,
                "additionalProperties", false);
    }
}
