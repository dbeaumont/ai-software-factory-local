package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Atomically accounts actual agent and MCP consumption across every attempt of a task. */
@Component
public final class TaskUsageLedger {
    private final HierarchicalBudgetPolicy.UsageQuota quota;
    private final HierarchicalBudgetPolicy.UsageQuota standardQuota;
    private final HierarchicalBudgetPolicy.UsageQuota finalizationQuota;
    private final ConcurrentMap<String, Counter> tasks = new ConcurrentHashMap<>();

    public TaskUsageLedger(HierarchicalBudgetPolicy policy) {
        this.quota = Objects.requireNonNull(policy).actualUsage();
        this.standardQuota = policy.standardUsage();
        this.finalizationQuota = policy.finalizationReserve();
    }

    public Snapshot consume(String taskId, String attemptId, Delta delta) {
        return consume(taskId, attemptId, Lane.STANDARD, delta);
    }

    public Snapshot consume(String taskId, String attemptId, Lane lane, Delta delta) {
        requireIdentity(taskId, attemptId);
        Objects.requireNonNull(lane);
        Objects.requireNonNull(delta).validate();
        return tasks.computeIfAbsent(taskId, ignored -> new Counter()).consume(attemptId, lane, delta,
                quota, standardQuota, finalizationQuota);
    }

    public Snapshot snapshot(String taskId) {
        Counter counter = tasks.get(taskId);
        return counter == null ? Snapshot.zero() : counter.snapshot();
    }

    public Snapshot snapshot(String taskId, Lane lane) {
        Counter counter = tasks.get(taskId);
        return counter == null ? Snapshot.zero() : counter.snapshot(lane);
    }

    public enum Lane { STANDARD, FINALIZATION }

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

    public static final class QuotaExceededException extends AgentToolLoop.AgentLoopException {
        private final String quota;

        QuotaExceededException(String quota) {
            super("task_quota", "Task cumulative quota exceeded: " + quota,
                    AgentToolLoop.StopCondition.BUDGET_EXHAUSTED);
            this.quota = quota;
        }

        public String quota() { return quota; }
    }

    private static final class Counter {
        private Snapshot standard = Snapshot.zero();
        private Snapshot finalization = Snapshot.zero();
        private String lastAttemptId;

        synchronized Snapshot consume(String attemptId, Lane lane, Delta delta,
                                      HierarchicalBudgetPolicy.UsageQuota totalQuota,
                                      HierarchicalBudgetPolicy.UsageQuota standardQuota,
                                      HierarchicalBudgetPolicy.UsageQuota finalizationQuota) {
            Snapshot currentLane = lane == Lane.STANDARD ? standard : finalization;
            Snapshot nextLane = add(currentLane, delta, attemptId);
            requireWithin(nextLane, lane == Lane.STANDARD ? standardQuota : finalizationQuota);
            Snapshot nextTotal = lane == Lane.STANDARD
                    ? add(nextLane, finalization, attemptId) : add(standard, nextLane, attemptId);
            requireWithin(nextTotal, totalQuota);
            if (lane == Lane.STANDARD) standard = nextLane; else finalization = nextLane;
            lastAttemptId = attemptId;
            return nextTotal;
        }

        synchronized Snapshot snapshot() { return add(standard, finalization, lastAttemptId); }
        synchronized Snapshot snapshot(Lane lane) { return lane == Lane.STANDARD ? standard : finalization; }

        private static Snapshot add(Snapshot value, Delta delta, String attemptId) {
            return new Snapshot(Math.addExact(value.inputTokens(), delta.inputTokens()),
                    Math.addExact(value.outputTokens(), delta.outputTokens()),
                    Math.addExact(value.costMicros(), delta.costMicros()),
                    Math.addExact(value.turns(), delta.turns()),
                    Math.addExact(value.mcpCalls(), delta.mcpCalls()), attemptId);
        }

        private static Snapshot add(Snapshot left, Snapshot right, String attemptId) {
            return new Snapshot(Math.addExact(left.inputTokens(), right.inputTokens()),
                    Math.addExact(left.outputTokens(), right.outputTokens()),
                    Math.addExact(left.costMicros(), right.costMicros()), Math.addExact(left.turns(), right.turns()),
                    Math.addExact(left.mcpCalls(), right.mcpCalls()), attemptId);
        }

        private static void requireWithin(Snapshot value, HierarchicalBudgetPolicy.UsageQuota quota) {
            if (value.inputTokens() > quota.maxInputTokens()) throw new QuotaExceededException("input_tokens");
            if (value.outputTokens() > quota.maxOutputTokens()) throw new QuotaExceededException("output_tokens");
            if (value.costMicros() > quota.maxCostMicros()) throw new QuotaExceededException("cost_micros");
            if (value.turns() > quota.maxTurns()) throw new QuotaExceededException("turns");
            if (value.mcpCalls() > quota.maxMcpCalls()) throw new QuotaExceededException("mcp_calls");
        }
    }
}
