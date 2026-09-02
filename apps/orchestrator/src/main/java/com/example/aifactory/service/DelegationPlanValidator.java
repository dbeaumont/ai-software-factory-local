package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Applies graph invariants that JSON Schema cannot express for a delegation plan. */
@Component
public final class DelegationPlanValidator {
    public JsonNode validate(JsonNode plan) {
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        for (JsonNode node : plan.path("nodes")) {
            String id = node.path("node_id").asText();
            if (id.isBlank() || nodes.putIfAbsent(id, node) != null) {
                throw invalid("duplicate or missing node_id: " + id);
            }
        }
        if (nodes.isEmpty()) throw invalid("delegation plan has no nodes");

        Map<String, Set<String>> edges = new HashMap<>();
        nodes.forEach((id, node) -> {
            Set<String> dependencies = new HashSet<>();
            JsonNode parent = node.path("parent_node_id");
            if (parent.isTextual()) dependencies.add(parent.asText());
            for (JsonNode dependency : node.path("depends_on")) dependencies.add(dependency.asText());
            for (String dependency : dependencies) {
                if (!nodes.containsKey(dependency)) {
                    throw invalid("orphan reference from " + id + " to " + dependency);
                }
                if (id.equals(dependency)) throw invalid("self dependency for " + id);
            }
            edges.put(id, Set.copyOf(dependencies));
        });

        Map<String, Visit> visits = new HashMap<>();
        for (String node : nodes.keySet()) visit(node, edges, visits);
        return plan;
    }

    private static void visit(String node, Map<String, Set<String>> edges, Map<String, Visit> visits) {
        Visit state = visits.get(node);
        if (state == Visit.DONE) return;
        if (state == Visit.ACTIVE) throw invalid("cycle detected at " + node);
        visits.put(node, Visit.ACTIVE);
        for (String dependency : edges.getOrDefault(node, Set.of())) visit(dependency, edges, visits);
        visits.put(node, Visit.DONE);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid delegation DAG: " + message);
    }

    private enum Visit { ACTIVE, DONE }
}
