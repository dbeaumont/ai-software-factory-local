package com.example.aifactory.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Host-controlled agent loop. The model can request tools, but cannot extend its own budgets. */
public final class AgentToolLoop {
    private final Model model;
    private final ToolExecutor tools;
    private final LongSupplier nanoTime;

    public AgentToolLoop(Model model, ToolExecutor tools) {
        this(model, tools, System::nanoTime);
    }

    AgentToolLoop(Model model, ToolExecutor tools, LongSupplier nanoTime) {
        this.model = Objects.requireNonNull(model);
        this.tools = Objects.requireNonNull(tools);
        this.nanoTime = Objects.requireNonNull(nanoTime);
    }

    public Result run(String systemPrompt, String userPrompt, Budget budget) {
        Objects.requireNonNull(budget).validate();
        long started = nanoTime.getAsLong();
        long deadline = Math.addExact(started, budget.deadline().toNanos());
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", systemPrompt, null));
        messages.add(new Message("user", userPrompt, null));
        int tokens = 0;
        long costMicros = 0;

        for (int turnNumber = 1; turnNumber <= budget.maxTurns(); turnNumber++) {
            requireWithinDeadline(deadline);
            Turn turn = Objects.requireNonNull(model.next(List.copyOf(messages)), "Model returned no turn");
            tokens = Math.addExact(tokens, Math.addExact(turn.promptTokens(), turn.completionTokens()));
            costMicros = Math.addExact(costMicros, turn.costMicros());
            requireWithinBudget(tokens, costMicros, budget);
            requireWithinDeadline(deadline);

            if (turn.stop() == Stop.FINAL) {
                if (!turn.toolCalls().isEmpty() || turn.finalResult() == null || turn.finalResult().isBlank()) {
                    throw new AgentLoopException("invalid_final", "A final turn must contain one non-empty result and no tool call");
                }
                return new Result(turn.finalResult(), turnNumber, tokens, costMicros);
            }
            if (turn.stop() != Stop.TOOL_CALLS || turn.toolCalls().isEmpty() || turn.finalResult() != null) {
                throw new AgentLoopException("invalid_stop", "The model must explicitly stop with FINAL or TOOL_CALLS");
            }

            messages.add(new Message("assistant", "", turn.toolCalls()));
            for (ToolCall call : turn.toolCalls()) {
                requireWithinDeadline(deadline);
                String output = tools.execute(call);
                messages.add(new Message("tool", output, List.of(call)));
            }
        }
        throw new AgentLoopException("max_turns", "Agent exceeded its maximum number of turns");
    }

    private void requireWithinDeadline(long deadline) {
        if (nanoTime.getAsLong() > deadline) {
            throw new AgentLoopException("deadline", "Agent exceeded its deadline");
        }
    }

    private static void requireWithinBudget(int tokens, long costMicros, Budget budget) {
        if (tokens > budget.maxTokens()) {
            throw new AgentLoopException("token_budget", "Agent exceeded its token budget");
        }
        if (costMicros > budget.maxCostMicros()) {
            throw new AgentLoopException("cost_budget", "Agent exceeded its cost budget");
        }
    }

    public interface Model {
        Turn next(List<Message> messages);
    }

    public interface ToolExecutor {
        String execute(ToolCall call);
    }

    public enum Stop { TOOL_CALLS, FINAL }

    public record Budget(int maxTurns, Duration deadline, int maxTokens, long maxCostMicros) {
        void validate() {
            if (maxTurns < 1 || deadline == null || deadline.isZero() || deadline.isNegative()
                    || maxTokens < 1 || maxCostMicros < 0) {
                throw new IllegalArgumentException("Agent budgets must be positive (cost may be zero)");
            }
        }
    }

    public record ToolCall(String id, String name, Map<String, Object> arguments) {
        public ToolCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    public record Turn(Stop stop, String finalResult, List<ToolCall> toolCalls,
                       int promptTokens, int completionTokens, long costMicros) {
        public Turn {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            if (promptTokens < 0 || completionTokens < 0 || costMicros < 0) {
                throw new IllegalArgumentException("Usage values cannot be negative");
            }
        }
    }

    public record Message(String role, String content, List<ToolCall> toolCalls) {
    }

    public record Result(String finalResult, int turns, int tokens, long costMicros) {
    }

    public static final class AgentLoopException extends RuntimeException {
        private final String reason;

        AgentLoopException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
