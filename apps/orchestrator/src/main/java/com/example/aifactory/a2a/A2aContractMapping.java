package com.example.aifactory.a2a;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/** Immutable role/skill/contract mapping pinned in the orchestrator artifact. */
@Component
public final class A2aContractMapping {
    private final Set<Key> inputs;
    private final Set<Key> primaryOutputs;
    private final java.util.Map<String, Set<String>> inputSkills;

    public A2aContractMapping(ObjectMapper mapper) {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("a2a/skill-contract-map-v1.json")) {
            if (input == null) throw new IllegalStateException("Missing A2A skill contract mapping");
            JsonNode catalog = mapper.readTree(input);
            if (!"1".equals(catalog.path("catalog_version").asText())) {
                throw new IllegalStateException("Unsupported A2A contract mapping");
            }
            Set<Key> loadedInputs = new HashSet<>();
            java.util.Map<String, java.util.Set<String>> loadedSkills = new java.util.LinkedHashMap<>();
            catalog.path("inputs").forEach(value -> {
                String role = value.path("role").asText();
                loadedInputs.add(new Key(role, value.path("input_contract").asText()));
                loadedSkills.computeIfAbsent(role, ignored -> new java.util.LinkedHashSet<>())
                        .add(value.path("skill_id").asText());
            });
            Set<Key> loadedOutputs = new HashSet<>();
            catalog.path("outputs").forEach(value -> {
                if (value.path("primary_artifact").asBoolean()) loadedOutputs.add(
                        new Key(value.path("role").asText(), value.path("output_contract").asText()));
            });
            inputs = Set.copyOf(loadedInputs);
            primaryOutputs = Set.copyOf(loadedOutputs);
            inputSkills = loadedSkills.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    java.util.Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load A2A contract mapping", exception);
        }
    }

    public void requireInput(String role, String contract) {
        if (!inputs.contains(new Key(role, contract))) {
            throw new IllegalArgumentException("Contract is not an A2A input for role " + role);
        }
    }

    public void requirePrimaryOutput(String role, String contract) {
        if (!primaryOutputs.contains(new Key(role, contract))) {
            throw new IllegalArgumentException("Contract is not the primary A2A output for role " + role);
        }
    }

    public Set<String> inputSkills(String role) {
        Set<String> skills = inputSkills.get(role);
        if (skills == null || skills.isEmpty()) throw new IllegalArgumentException("Role has no A2A input skill");
        return skills;
    }

    private record Key(String role, String contract) {}
}
