package com.example.aifactory.a2a;

import com.example.aifactory.workflow.temporal.DelegationWorkflow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the single structured instruction part admitted by every A2A agent runtime. */
public final class A2aEnvelopeFactory {
    private static final long MAX_INPUT_BYTES = 1_048_576;

    private A2aEnvelopeFactory() { }

    public static A2aContracts.Part create(String role, String skill, String outputContract,
                                           List<A2aContracts.Part> references,
                                           DelegationWorkflow.Budget budget) {
        if (references == null || references.isEmpty()) {
            throw new IllegalArgumentException("An A2A envelope requires at least one Evidence reference");
        }
        List<Map<String, Object>> inputs = references.stream().map(A2aEnvelopeFactory::reference).toList();
        List<String> allowed = inputs.stream().map(value -> String.valueOf(value.get("reference_id")))
                .distinct().toList();
        Map<String, Object> constraints = Map.of(
                "expected_output_contract", outputContract,
                "allowed_reference_ids", allowed,
                "max_input_bytes", MAX_INPUT_BYTES);
        Map<String, Object> limits = Map.of(
                "max_turns", budget.maxTurns(),
                "max_tokens", budget.maxTokens(),
                "max_cost_micros", budget.maxCostMicros(),
                "timeout_seconds", budget.timeoutSeconds(),
                "max_tool_calls", Math.min(256, budget.maxTurns() * 8));
        Map<String, Object> envelope = Map.of(
                "schema_version", "1",
                "target_role", role,
                "skill_id", skill,
                "input_references", inputs,
                "constraints", constraints,
                "budget", limits);
        return new A2aContracts.Part(A2aMediaTypes.JSON, null, envelope, null);
    }

    private static Map<String, Object> reference(A2aContracts.Part part) {
        if (part == null || !A2aMediaTypes.EVIDENCE_REFERENCE.equals(part.mediaType()) || part.uri() == null) {
            throw new IllegalArgumentException("A2A input must be an Evidence reference part");
        }
        Map<String, Object> data = part.data();
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("schema_version", "reference_id", "uri", "digest", "size_bytes",
                "media_type", "classification", "contract", "contract_version")) {
            Object value = data.get(field);
            if (value == null) throw new IllegalArgumentException("A2A Evidence reference lacks " + field);
            result.put(field, value);
        }
        if (!part.uri().toString().equals(result.get("uri"))) {
            throw new SecurityException("A2A Evidence reference URI fields diverge");
        }
        return Map.copyOf(result);
    }
}
