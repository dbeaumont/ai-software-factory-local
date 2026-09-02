package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable host policy for agent, delegation, perimeter and whole-task budgets. */
@Component
public final class HierarchicalBudgetPolicy {
    private static final String RESOURCE = "multiagents/policies/hierarchical-budget-policy-v1.yaml";
    private final String policyId;
    private final Budget delegation;
    private final Map<String, Budget> agents;
    private final Map<String, AggregateBudget> perimeters;
    private final AggregateBudget task;
    private final UsageQuota actualUsage;
    private final UsageQuota finalizationReserve;

    public HierarchicalBudgetPolicy() {
        Map<String, Object> root = load();
        if (!"1".equals(root.get("version"))) throw new IllegalStateException("Unsupported budget policy version");
        policyId = required(root, "policyId");
        delegation = budget(map(root.get("delegation"), "delegation"));
        agents = Map.copyOf(budgets(map(root.get("agents"), "agents")));
        perimeters = Map.copyOf(aggregateBudgets(map(root.get("perimeters"), "perimeters")));
        task = aggregateBudget(map(root.get("task"), "task"));
        Map<String, Object> usage = map(root.get("actual_usage"), "actual_usage");
        actualUsage = new UsageQuota(number(usage, "max_input_tokens"), number(usage, "max_output_tokens"),
                number(usage, "max_cost_micros"), number(usage, "max_turns"),
                number(usage, "max_mcp_calls"));
        Map<String, Object> reserve = map(root.get("finalization_reserve"), "finalization_reserve");
        finalizationReserve = new UsageQuota(number(reserve, "max_input_tokens"),
                number(reserve, "max_output_tokens"), number(reserve, "max_cost_micros"),
                number(reserve, "max_turns"), number(reserve, "max_mcp_calls"));
        actualUsage.minus(finalizationReserve);
    }

    public String policyId() { return policyId; }
    public Map<String, Budget> agents() { return agents; }
    public Map<String, AggregateBudget> perimeters() { return perimeters; }
    public AggregateBudget task() { return task; }
    public UsageQuota actualUsage() { return actualUsage; }
    public UsageQuota finalizationReserve() { return finalizationReserve; }
    public UsageQuota standardUsage() { return actualUsage.minus(finalizationReserve); }

    public void validateInvocation(String role, AgentToolLoop.Budget requested) {
        Budget ceiling = agents.get(role);
        if (ceiling == null) throw invalid("agent role has no budget: " + role);
        if (requested.maxTurns() > ceiling.maxTurns()
                || requested.maxTokens() > ceiling.maxTokens()
                || requested.maxCostMicros() > ceiling.maxCostMicros()
                || requested.deadline().compareTo(Duration.ofSeconds(ceiling.timeoutSeconds())) > 0) {
            throw invalid("agent budget exceeded for " + role);
        }
    }

    public Usage validateDelegation(String role, JsonNode value) {
        Budget requested = requested(value);
        Budget roleCeiling = agents.get(role);
        if (roleCeiling == null) throw invalid("agent role has no budget: " + role);
        requireWithin(requested, delegation, "delegation");
        requireWithin(requested, roleCeiling, "agent " + role);
        return new Usage(requested.maxTurns(), requested.maxTokens(), requested.maxCostMicros(),
                requested.maxToolCalls());
    }

    public void validateChildDelegation(JsonNode child, JsonNode parent) {
        requireWithin(requested(child), requested(parent), "child delegation");
    }

    public void validatePerimeter(String perimeter, Usage usage) {
        AggregateBudget ceiling = perimeters.get(perimeter);
        if (ceiling == null) throw invalid("unknown budget perimeter: " + perimeter);
        requireWithin(usage, ceiling, "perimeter " + perimeter);
    }

    public void validateTask(Usage usage) {
        requireWithin(usage, task.minus(finalizationReserve), "task before finalization reserve");
    }

    private static void requireWithin(Budget requested, Budget ceiling, String level) {
        if (requested.maxTurns() > ceiling.maxTurns() || requested.maxTokens() > ceiling.maxTokens()
                || requested.maxCostMicros() > ceiling.maxCostMicros()
                || requested.timeoutSeconds() > ceiling.timeoutSeconds()
                || requested.maxToolCalls() > ceiling.maxToolCalls()) {
            throw invalid(level + " budget exceeded");
        }
    }

    private static void requireWithin(Usage usage, AggregateBudget ceiling, String level) {
        if (usage.turns() > ceiling.maxTurns() || usage.tokens() > ceiling.maxTokens()
                || usage.costMicros() > ceiling.maxCostMicros() || usage.toolCalls() > ceiling.maxToolCalls()) {
            throw invalid(level + " budget exceeded");
        }
    }

    private static Map<String, Budget> budgets(Map<String, Object> values) {
        Map<String, Budget> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, budget(map(value, "agent " + key))));
        return result;
    }

    private static Map<String, AggregateBudget> aggregateBudgets(Map<String, Object> values) {
        Map<String, AggregateBudget> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, aggregateBudget(map(value, "perimeter " + key))));
        return result;
    }

    private static Budget budget(Map<String, Object> value) {
        return new Budget(number(value, "max_turns"), number(value, "max_tokens"),
                number(value, "max_cost_micros"), number(value, "timeout_seconds"),
                number(value, "max_tool_calls"));
    }

    private static Budget requested(JsonNode value) {
        return new Budget(value.path("max_turns").asLong(), value.path("max_tokens").asLong(),
                value.path("max_cost_micros").asLong(), value.path("timeout_seconds").asLong(),
                value.path("max_tool_calls").asLong());
    }

    private static AggregateBudget aggregateBudget(Map<String, Object> value) {
        return new AggregateBudget(number(value, "max_turns"), number(value, "max_tokens"),
                number(value, "max_cost_micros"), number(value, "max_tool_calls"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        try (InputStream input = HierarchicalBudgetPolicy.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new IllegalStateException("Missing " + RESOURCE);
            return map(new Yaml(new SafeConstructor(new LoaderOptions())).load(input), "budget policy");
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load hierarchical budget policy", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String field) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalStateException(field + " must be an object");
        return (Map<String, Object>) value;
    }

    private static String required(Map<String, Object> value, String field) {
        Object result = value.get(field);
        if (result == null || result.toString().isBlank()) throw new IllegalStateException(field + " is required");
        return result.toString();
    }

    private static long number(Map<String, Object> value, String field) {
        Object result = value.get(field);
        if (!(result instanceof Number number)) throw new IllegalStateException(field + " must be numeric");
        return number.longValue();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid hierarchical budget: " + message);
    }

    public record Budget(long maxTurns, long maxTokens, long maxCostMicros, long timeoutSeconds,
                         long maxToolCalls) {
        public Budget {
            if (maxTurns < 1 || maxTokens < 1 || maxCostMicros < 0 || timeoutSeconds < 1 || maxToolCalls < 0) {
                throw new IllegalArgumentException("Budget ceilings must be positive (cost and calls may be zero)");
            }
        }
    }

    public record AggregateBudget(long maxTurns, long maxTokens, long maxCostMicros, long maxToolCalls) {
        public AggregateBudget {
            if (maxTurns < 1 || maxTokens < 1 || maxCostMicros < 0 || maxToolCalls < 0) {
                throw new IllegalArgumentException("Aggregate budget ceilings must be positive");
            }
        }

        AggregateBudget minus(UsageQuota reserved) {
            return new AggregateBudget(maxTurns - reserved.maxTurns,
                    maxTokens - reserved.maxInputTokens - reserved.maxOutputTokens,
                    maxCostMicros - reserved.maxCostMicros, maxToolCalls - reserved.maxMcpCalls);
        }
    }

    public record Usage(long turns, long tokens, long costMicros, long toolCalls) {
        public Usage plus(Usage other) {
            return new Usage(Math.addExact(turns, other.turns), Math.addExact(tokens, other.tokens),
                    Math.addExact(costMicros, other.costMicros), Math.addExact(toolCalls, other.toolCalls));
        }

        public static Usage zero() { return new Usage(0, 0, 0, 0); }
    }

    public record UsageQuota(long maxInputTokens, long maxOutputTokens, long maxCostMicros,
                             long maxTurns, long maxMcpCalls) {
        public UsageQuota {
            if (maxInputTokens < 1 || maxOutputTokens < 1 || maxCostMicros < 0
                    || maxTurns < 1 || maxMcpCalls < 0) {
                throw new IllegalArgumentException("Actual usage quotas must be positive");
            }
        }

        UsageQuota minus(UsageQuota reserved) {
            return new UsageQuota(maxInputTokens - reserved.maxInputTokens,
                    maxOutputTokens - reserved.maxOutputTokens,
                    maxCostMicros - reserved.maxCostMicros,
                    maxTurns - reserved.maxTurns, maxMcpCalls - reserved.maxMcpCalls);
        }
    }
}
