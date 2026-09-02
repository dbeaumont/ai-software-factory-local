package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Atomically accounts actual agent and MCP consumption across every attempt of a task. */
@Component
public final class TaskUsageLedger {
    private final HierarchicalBudgetPolicy.UsageQuota quota;
    private final ConcurrentMap<String, Counter> tasks = new ConcurrentHashMap<>();

    public TaskUsageLedger(HierarchicalBudgetPolicy policy) {
        this.quota = Objects.requireNonNull(policy).actualUsage();
    }

    public Snapshot consume(String taskId, String attemptId, Delta delta) {
        requireIdentity(taskId, attemptId);
        Objects.requireNonNull(delta).validate();
        return tasks.computeIfAbsent(taskId, ignored -> new Counter()).consume(attemptId, delta, quota);
    }

    public Snapshot snapshot(String taskId) {
        Counter counter = tasks.get(taskId);
        return counter == null ? Snapshot.zero() : counter.snapshot();
    }

    private static void requireIdentity(String taskId, String attemptId) {
        if (taskId == null || taskId.isBlank() || attemptId == null || attemptId.isBlank()) {
            throw new IllegalArgumentException("Task usage requires task and attempt identities");
        }
    }

    public record Delta(long inputTokens, long outputTokens, long costMicros, long turns, long mcpCalls) {
        void validate() {
            if (inputTokens < 0 || outputTokens < 0 || costMicros < 0 || turns < 0 || mcpCalls < 0) {
                throw new IllegalArgumentException("Task usage deltas cannot be negative");
            }
        }
    }

    public record Snapshot(long inputTokens, long outputTokens, long costMicros, long turns,
                           long mcpCalls, String lastAttemptId) {
        static Snapshot zero() { return new Snapshot(0, 0, 0, 0, 0, null); }
    }

    public static final class QuotaExceededException extends RuntimeException {
        private final String quota;

        QuotaExceededException(String quota) {
            super("Task cumulative quota exceeded: " + quota);
            this.quota = quota;
        }

        public String quota() { return quota; }
    }

    private static final class Counter {
        private Snapshot value = Snapshot.zero();

        synchronized Snapshot consume(String attemptId, Delta delta, HierarchicalBudgetPolicy.UsageQuota quota) {
            Snapshot next = new Snapshot(
                    Math.addExact(value.inputTokens(), delta.inputTokens()),
                    Math.addExact(value.outputTokens(), delta.outputTokens()),
                    Math.addExact(value.costMicros(), delta.costMicros()),
                    Math.addExact(value.turns(), delta.turns()),
                    Math.addExact(value.mcpCalls(), delta.mcpCalls()), attemptId);
            if (next.inputTokens() > quota.maxInputTokens()) throw new QuotaExceededException("input_tokens");
            if (next.outputTokens() > quota.maxOutputTokens()) throw new QuotaExceededException("output_tokens");
            if (next.costMicros() > quota.maxCostMicros()) throw new QuotaExceededException("cost_micros");
            if (next.turns() > quota.maxTurns()) throw new QuotaExceededException("turns");
            if (next.mcpCalls() > quota.maxMcpCalls()) throw new QuotaExceededException("mcp_calls");
            value = next;
            return value;
        }

        synchronized Snapshot snapshot() { return value; }
    }
}
