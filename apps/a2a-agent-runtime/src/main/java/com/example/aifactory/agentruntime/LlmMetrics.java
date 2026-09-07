package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentLoop;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Bounded, content-free telemetry for one OpenAI-compatible completion attempt. */
final class LlmMetrics {
    private static final String REQUESTS = "ai_factory_llm_requests_total";
    private static final String DURATION = "ai_factory_llm_request_duration_seconds";
    private static final String TOKENS = "ai_factory_llm_tokens_total";
    private static final String COST = "ai_factory_llm_cost_micros_total";
    private static final String COST_AVAILABILITY = "ai_factory_llm_cost_availability_total";
    private final MeterRegistry registry;
    private final String[] identity;

    LlmMetrics(MeterRegistry registry, LlmAdapterProperties properties) {
        this.registry = registry;
        this.identity = new String[]{"provider", properties.provider(), "model", properties.model()};
    }

    void record(String outcome, Duration duration, AgentLoop.Turn turn, JsonNode response) {
        if (!List.of("success", "error", "timeout").contains(outcome)) {
            throw new IllegalArgumentException("Unexpected LLM outcome");
        }
        Counter.builder(REQUESTS).tags(with("outcome", outcome)).register(registry).increment();
        Timer.builder(DURATION).publishPercentileHistogram().tags(identity).register(registry).record(duration);
        if (turn == null) return;
        increment(TOKENS, turn.promptTokens(), "direction", "input");
        increment(TOKENS, turn.completionTokens(), "direction", "output");
        Optional<Cost> cost = cost(response);
        Counter.builder(COST_AVAILABILITY).tags(with("status", cost.isPresent() ? "available" : "unavailable"))
                .register(registry).increment();
        cost.ifPresent(value -> increment(COST, value.micros(), "currency", value.currency()));
    }

    private void increment(String name, long amount, String tag, String value) {
        if (amount > 0) Counter.builder(name).tags(with(tag, value)).register(registry).increment(amount);
    }

    private String[] with(String tag, String value) {
        String[] tags = java.util.Arrays.copyOf(identity, identity.length + 2);
        tags[identity.length] = tag;
        tags[identity.length + 1] = value;
        return tags;
    }

    static Optional<Cost> cost(JsonNode response) {
        if (response == null) return Optional.empty();
        JsonNode hidden = response.path("_hidden_params");
        JsonNode amount = hidden.has("response_cost") ? hidden.path("response_cost") : response.path("response_cost");
        JsonNode rawCurrency = hidden.has("currency") ? hidden.path("currency") : response.path("currency");
        if (!amount.isNumber() || !Double.isFinite(amount.asDouble()) || amount.asDouble() < 0
                || !rawCurrency.isTextual()) return Optional.empty();
        String currency = rawCurrency.asText().strip().toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) return Optional.empty();
        return Optional.of(new Cost(Math.round(amount.asDouble() * 1_000_000), currency));
    }

    record Cost(long micros, String currency) {}
}
