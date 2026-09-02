package com.example.aifactory.service;

import com.example.aifactory.config.DelegationPolicyProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Enforces host ceilings on a model-proposed delegation plan. */
@Component
public final class DelegationValidator {
    private final AgentCatalog catalog;
    private final DelegationPlanValidator graphs;
    private final DelegationPolicyProperties ceilings;

    public DelegationValidator(AgentCatalog catalog, DelegationPlanValidator graphs) {
        this(catalog, graphs, DelegationPolicyProperties.defaults());
    }

    @Autowired
    public DelegationValidator(AgentCatalog catalog, DelegationPlanValidator graphs,
                               DelegationPolicyProperties ceilings) {
        this.catalog = catalog;
        this.graphs = graphs;
        this.ceilings = ceilings;
    }

    public JsonNode validate(JsonNode plan, Limits limits) {
        graphs.validate(plan);
        Map<String, JsonNode> nodes = new LinkedHashMap<>();
        for (JsonNode node : plan.path("nodes")) nodes.put(node.path("node_id").asText(), node);
        long tokens = 0;
        long cost = 0;
        Map<String, Integer> children = new HashMap<>();

        for (Map.Entry<String, JsonNode> entry : nodes.entrySet()) {
            String id = entry.getKey();
            JsonNode node = entry.getValue();
            String roleName = node.path("role").asText();
            AgentCatalog.Role role = catalog.require(roleName);
            if (!limits.allowedRoles().contains(roleName)) throw invalid("role is outside host allow-list: " + roleName);

            JsonNode parentId = node.path("parent_node_id");
            if (parentId.isNull()) {
                if (!"supervisor".equals(role.parent())) throw invalid("role is not a direct Supervisor child: " + roleName);
                children.merge("$supervisor", 1, Integer::sum);
            } else {
                JsonNode parent = nodes.get(parentId.asText());
                String parentRole = parent.path("role").asText();
                if (!parentRole.equals(role.parent()) || !catalog.require(parentRole).mayDelegateTo().contains(roleName))
                    throw invalid("invalid parent for " + roleName);
                children.merge(parentId.asText(), 1, Integer::sum);
            }

            validatePaths(id, node.path("scope").path("read_paths"), limits.allowedReadRoots(), "read");
            validatePaths(id, node.path("scope").path("write_paths"), limits.allowedWriteRoots(), "write");
            tokens = Math.addExact(tokens, node.path("budget").path("max_tokens").asLong());
            cost = Math.addExact(cost, node.path("budget").path("max_cost_micros").asLong());
            int effectiveMaxDepth = Math.min(limits.maxDepth(), ceilings.maxDepth());
            if (depth(id, nodes, new java.util.HashSet<>()) > effectiveMaxDepth)
                throw invalid("maximum depth exceeded at " + id);
        }
        int effectiveMaxFanOut = Math.min(limits.maxFanOut(), ceilings.maxFanOut());
        if (children.values().stream().anyMatch(value -> value > effectiveMaxFanOut))
            throw invalid("maximum fan-out exceeded");
        if (tokens > limits.maxTotalTokens()) throw invalid("total token budget exceeded");
        if (cost > limits.maxTotalCostMicros()) throw invalid("total cost budget exceeded");
        return plan;
    }

    private static int depth(String id, Map<String, JsonNode> nodes, Set<String> visiting) {
        if (!visiting.add(id)) throw invalid("parent cycle at " + id);
        JsonNode parent = nodes.get(id).path("parent_node_id");
        int result = parent.isNull() ? 1 : 1 + depth(parent.asText(), nodes, visiting);
        visiting.remove(id);
        return result;
    }

    private static void validatePaths(String node, JsonNode paths, List<String> roots, String access) {
        for (JsonNode path : paths) {
            String value = path.asText();
            boolean allowed = roots.stream().anyMatch(root -> value.equals(root) || value.startsWith(root + "/"));
            if (!allowed) throw invalid(access + " scope outside host boundary for " + node + ": " + value);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid delegation: " + message);
    }

    public record Limits(Set<String> allowedRoles, List<String> allowedReadRoots, List<String> allowedWriteRoots,
                         int maxDepth, int maxFanOut, long maxTotalTokens, long maxTotalCostMicros) {
        public Limits {
            allowedRoles = Set.copyOf(allowedRoles);
            allowedReadRoots = List.copyOf(allowedReadRoots);
            allowedWriteRoots = List.copyOf(allowedWriteRoots);
            if (maxDepth < 1 || maxFanOut < 1 || maxTotalTokens < 1 || maxTotalCostMicros < 0)
                throw new IllegalArgumentException("Delegation limits must be positive");
        }
    }
}
