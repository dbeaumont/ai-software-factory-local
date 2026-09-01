package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentToolLoopTest {
    private static final AgentToolLoop.Budget BUDGET =
            new AgentToolLoop.Budget(3, Duration.ofSeconds(2), 1_000, 50_000);

    @Test
    void executesToolsThenRequiresAnExplicitFinalResult() {
        var turns = new ArrayDeque<>(List.of(
                new AgentToolLoop.Turn(AgentToolLoop.Stop.TOOL_CALLS, null,
                        List.of(new AgentToolLoop.ToolCall("call-1", "context.read_file", Map.of("path", "README.md"))),
                        100, 20, 2_000),
                new AgentToolLoop.Turn(AgentToolLoop.Stop.FINAL, "plan final", List.of(), 140, 30, 3_000)));
        AgentToolLoop loop = new AgentToolLoop(messages -> turns.removeFirst(), call -> "safe-result");

        AgentToolLoop.Result result = loop.run("system", "ticket", BUDGET);

        assertEquals("plan final", result.finalResult());
        assertEquals(2, result.turns());
        assertEquals(290, result.tokens());
        assertEquals(5_000, result.costMicros());
    }

    @Test
    void failsClosedOnTurnsTokensCostDeadlineAndMissingFinal() {
        AgentToolLoop loop = new AgentToolLoop(messages -> toolTurn(), call -> "ok");
        assertEquals("max_turns", exceptionFor(loop,
                new AgentToolLoop.Budget(1, Duration.ofSeconds(1), 1_000, 10_000)).reason());
    }

    @Test
    void rejectsEveryBudgetBoundaryIndependently() {
        assertEquals("token_budget", exceptionFor(
                new AgentToolLoop(messages -> finalTurn(101, 0, 0), call -> "ok"),
                new AgentToolLoop.Budget(1, Duration.ofSeconds(1), 100, 10)).reason());
        assertEquals("cost_budget", exceptionFor(
                new AgentToolLoop(messages -> finalTurn(1, 1, 11), call -> "ok"),
                new AgentToolLoop.Budget(1, Duration.ofSeconds(1), 100, 10)).reason());

        AtomicLong clock = new AtomicLong();
        AgentToolLoop deadlineLoop = new AgentToolLoop(messages -> {
            clock.set(Duration.ofSeconds(2).toNanos());
            return finalTurn(1, 1, 0);
        }, call -> "ok", (actor, tool) -> true, AgentToolLoop.SafetyLimits.defaults(), clock::get);
        assertEquals("deadline", exceptionFor(deadlineLoop,
                new AgentToolLoop.Budget(1, Duration.ofSeconds(1), 100, 10)).reason());

        AgentToolLoop invalidFinal = new AgentToolLoop(
                messages -> new AgentToolLoop.Turn(AgentToolLoop.Stop.FINAL, "", List.of(), 1, 1, 0),
                call -> "ok");
        assertEquals("invalid_final", exceptionFor(invalidFinal, BUDGET).reason());
    }

    @Test
    void hostRoleDeniesUnauthorizedToolBeforeExecution() {
        AtomicInteger executions = new AtomicInteger();
        AgentToolLoop loop = new AgentToolLoop(messages -> new AgentToolLoop.Turn(
                AgentToolLoop.Stop.TOOL_CALLS, null,
                List.of(new AgentToolLoop.ToolCall("call-effect", "sandbox.apply_patch", Map.of())),
                1, 1, 0), call -> {
            executions.incrementAndGet();
            return "must-not-run";
        }, ToolPermissionMatrix.readOnlyAgents());

        AgentToolLoop.AgentLoopException error = assertThrows(AgentToolLoop.AgentLoopException.class,
                () -> loop.run(new AgentToolLoop.Actor("task-172", "planner"), "system", "user", BUDGET));

        assertEquals("tool_denied", error.reason());
        assertEquals(0, executions.get());
    }

    @Test
    void detectsFanOutRepeatedCallsAndContextExplosion() {
        AgentToolLoop fanOut = loopReturning(List.of(tool("1", "a"), tool("2", "b")), "ok",
                new AgentToolLoop.SafetyLimits(1, 2, 100));
        assertEquals("fan_out", exceptionFor(fanOut, BUDGET).reason());

        AgentToolLoop repeated = loopReturning(List.of(tool("ignored-id", "same")), "ok",
                new AgentToolLoop.SafetyLimits(2, 1, 100));
        assertEquals("repeated_call", exceptionFor(repeated, BUDGET).reason());

        AgentToolLoop oversized = loopReturning(List.of(tool("1", "context")), "x".repeat(101),
                new AgentToolLoop.SafetyLimits(2, 2, 100));
        assertEquals("context_limit", exceptionFor(oversized, BUDGET).reason());
    }

    private static AgentToolLoop loopReturning(List<AgentToolLoop.ToolCall> calls, String result,
                                               AgentToolLoop.SafetyLimits limits) {
        return new AgentToolLoop(messages -> new AgentToolLoop.Turn(
                AgentToolLoop.Stop.TOOL_CALLS, null, calls, 1, 1, 0), call -> result,
                (actor, tool) -> true, limits);
    }

    private static AgentToolLoop.ToolCall tool(String id, String value) {
        return new AgentToolLoop.ToolCall(id, "context.read_file", Map.of("path", value));
    }

    private static AgentToolLoop.AgentLoopException exceptionFor(AgentToolLoop loop, AgentToolLoop.Budget budget) {
        return assertThrows(AgentToolLoop.AgentLoopException.class, () -> loop.run("system", "user", budget));
    }

    private static AgentToolLoop.Turn toolTurn() {
        return new AgentToolLoop.Turn(AgentToolLoop.Stop.TOOL_CALLS, null,
                List.of(new AgentToolLoop.ToolCall("call", "context.read_file", Map.of())), 1, 1, 0);
    }

    private static AgentToolLoop.Turn finalTurn(int prompt, int completion, long cost) {
        return new AgentToolLoop.Turn(AgentToolLoop.Stop.FINAL, "done", List.of(), prompt, completion, cost);
    }
}
