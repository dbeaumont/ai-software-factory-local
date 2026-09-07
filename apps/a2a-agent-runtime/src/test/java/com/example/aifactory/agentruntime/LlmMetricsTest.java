package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentLoop;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LlmMetricsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void recordsBoundedRequestLatencyTokenAndAttestedCostMetrics() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmMetrics metrics = new LlmMetrics(registry, new LlmAdapterProperties(
                "http://litellm:4000/v1", "", "litellm", "factory-code-cloud", 8192, Duration.ofMinutes(1)));
        AgentLoop.Turn turn = new AgentLoop.Turn(AgentLoop.Stop.FINAL, "{}", List.of(), 13, 7, 23);

        metrics.record("success", Duration.ofMillis(25), turn, mapper.readTree("""
                {"response_cost":0.000023,"currency":"usd"}
                """));

        assertThat(registry.find("ai_factory_llm_requests_total").counter().count()).isEqualTo(1);
        assertThat(registry.find("ai_factory_llm_request_duration_seconds").timer().count()).isEqualTo(1);
        assertThat(registry.find("ai_factory_llm_tokens_total").counters())
                .extracting(counter -> counter.count()).containsExactlyInAnyOrder(13.0, 7.0);
        assertThat(registry.find("ai_factory_llm_cost_micros_total").counter().count()).isEqualTo(23);
        assertThat(registry.find("ai_factory_llm_cost_availability_total").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).flatExtracting(meter -> meter.getId().getTags())
                .extracting(io.micrometer.core.instrument.Tag::getKey)
                .containsOnly("provider", "model", "outcome", "direction", "currency", "status");
    }

    @Test
    void doesNotTurnUnknownCostIntoZero() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmMetrics metrics = new LlmMetrics(registry, new LlmAdapterProperties(
                "http://litellm:4000/v1", "", "litellm", "factory-code-cloud", 8192, Duration.ofMinutes(1)));

        metrics.record("error", Duration.ZERO, null, mapper.readTree("{\"response_cost\":0}"));

        assertThat(registry.find("ai_factory_llm_cost_micros_total").counters()).isEmpty();
        assertThat(registry.find("ai_factory_llm_cost_availability_total").counter().getId().getTag("status"))
                .isEqualTo("unavailable");
    }
}
