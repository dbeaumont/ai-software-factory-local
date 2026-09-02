package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** Enforces acceptance-criterion traceability that JSON Schema cannot express. */
@Component
public final class TestStrategyValidator {
    public JsonNode validate(JsonNode strategy) {
        Set<String> criteria = new LinkedHashSet<>();
        for (JsonNode criterion : strategy.path("acceptance_criteria")) {
            String id = criterion.path("criterion_id").asText();
            if (id.isBlank() || !criteria.add(id)) throw invalid("duplicate or missing criterion " + id);
        }
        Set<String> covered = new HashSet<>();
        for (JsonNode testCase : strategy.path("test_cases")) {
            for (JsonNode reference : testCase.path("covers_criteria")) {
                String id = reference.asText();
                if (!criteria.contains(id)) throw invalid("unknown criterion " + id);
                covered.add(id);
            }
        }
        Set<String> missing = new LinkedHashSet<>(criteria);
        missing.removeAll(covered);
        if (!missing.isEmpty()) throw invalid("uncovered acceptance criteria " + missing);
        return strategy;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid test strategy: " + reason);
    }
}
