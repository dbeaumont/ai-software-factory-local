package com.example.aifactory.service;

import com.example.aifactory.config.McpClientProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientMcpToolInvokerTest {
    private ResilientMcpToolInvoker invoker;

    @AfterEach
    void closeExecutor() {
        if (invoker != null) {
            invoker.close();
        }
    }

    @Test
    void retriesOnlyRetryableFailuresAndRecordsLowCardinalityMetrics() {
        AtomicInteger calls = new AtomicInteger();
        McpToolInvoker delegate = delegate((server, tool, arguments) -> {
            if (calls.incrementAndGet() == 1) {
                throw new McpInvocationException("DEPENDENCY_UNAVAILABLE", true, "temporary failure");
            }
            return new ObjectMapper().createObjectNode().put("ok", true);
        });
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        invoker = new ResilientMcpToolInvoker(delegate, properties(3, Duration.ofSeconds(1)), metrics,
                new ObjectMapper());

        JsonNode response = invoker.call("repository-context-mcp", "context.list_tree", arguments());

        assertThat(response.path("ok").asBoolean()).isTrue();
        assertThat(calls).hasValue(2);
        assertThat(metrics.get("mcp_client_retries").counter().count()).isEqualTo(1);
        assertThat(metrics.get("mcp_client_calls").tag("outcome", "success").counter().count()).isEqualTo(1);
    }

    @Test
    void enforcesThePerServerTimeout() {
        McpToolInvoker delegate = delegate((server, tool, arguments) -> {
            long end = System.nanoTime() + Duration.ofMillis(200).toNanos();
            while (System.nanoTime() < end) {
                Thread.onSpinWait();
            }
            return new ObjectMapper().createObjectNode();
        });
        invoker = new ResilientMcpToolInvoker(delegate, properties(1, Duration.ofMillis(20)),
                new SimpleMeterRegistry(), new ObjectMapper());

        assertThatThrownBy(() -> invoker.call("repository-context-mcp", "context.list_tree", arguments()))
                .isInstanceOf(McpInvocationException.class)
                .extracting(error -> ((McpInvocationException) error).code())
                .isEqualTo("TIMEOUT");
    }

    @Test
    void doesNotRetryAResponseSchemaFailure() {
        AtomicInteger calls = new AtomicInteger();
        McpToolInvoker delegate = delegate((server, tool, arguments) -> {
            calls.incrementAndGet();
            throw new McpResponseValidator.McpResponseValidationException(tool, "malformed");
        });
        invoker = new ResilientMcpToolInvoker(delegate, properties(3, Duration.ofSeconds(1)),
                new SimpleMeterRegistry(), new ObjectMapper());

        assertThatThrownBy(() -> invoker.call("repository-context-mcp", "context.list_tree", arguments()))
                .isInstanceOf(McpResponseValidator.McpResponseValidationException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void keepsTheConcurrencyPermitUntilATimedOutUnderlyingCallActuallyStops() {
        McpToolInvoker delegate = delegate((server, tool, arguments) -> {
            long end = System.nanoTime() + Duration.ofMillis(200).toNanos();
            while (System.nanoTime() < end) {
                Thread.onSpinWait();
            }
            return new ObjectMapper().createObjectNode();
        });
        invoker = new ResilientMcpToolInvoker(delegate, properties(1, Duration.ofMillis(20)),
                new SimpleMeterRegistry(), new ObjectMapper());

        assertThatThrownBy(() -> invoker.call("repository-context-mcp", "context.list_tree", arguments()))
                .isInstanceOf(McpInvocationException.class);
        assertThatThrownBy(() -> invoker.call("repository-context-mcp", "context.list_tree", arguments()))
                .isInstanceOf(McpInvocationException.class)
                .extracting(error -> ((McpInvocationException) error).code())
                .isEqualTo("LIMIT_EXCEEDED");
    }

    @Test
    void opensTheCircuitAfterFiveTerminalDependencyFailures() {
        AtomicInteger calls = new AtomicInteger();
        McpToolInvoker delegate = delegate((server, tool, arguments) -> {
            calls.incrementAndGet();
            throw new McpInvocationException("DEPENDENCY_UNAVAILABLE", true, "temporary failure");
        });
        invoker = new ResilientMcpToolInvoker(delegate, properties(1, Duration.ofSeconds(1)),
                new SimpleMeterRegistry(), new ObjectMapper());

        for (int index = 0; index < 5; index++) {
            assertThatThrownBy(() -> invoker.call("repository-context-mcp", "context.list_tree", arguments()))
                    .isInstanceOf(McpInvocationException.class);
        }
        assertThatThrownBy(() -> invoker.call("repository-context-mcp", "context.list_tree", arguments()))
                .isInstanceOf(McpInvocationException.class)
                .extracting(error -> ((McpInvocationException) error).code())
                .isEqualTo("CIRCUIT_OPEN");
        assertThat(calls).hasValue(5);
    }

    private static Map<String, Object> arguments() {
        return Map.of(
                "task_id", "task-1",
                "attempt_id", "attempt-1",
                "actor", "workflow",
                "deadline", Instant.now().plusSeconds(5).toString());
    }

    private static McpToolInvoker delegate(Call call) {
        return new McpToolInvoker() {
            @Override
            public JsonNode call(String serverName, String toolName, Map<String, Object> arguments) {
                return call.invoke(serverName, toolName, arguments);
            }

            @Override
            public Availability availability(String serverName) {
                return new Availability(true, null);
            }
        };
    }

    private static McpClientProperties properties(int readAttempts, Duration timeout) {
        McpClientProperties.RetryPolicy readOnly = new McpClientProperties.RetryPolicy(
                readAttempts, Duration.ofMillis(1), Duration.ofMillis(2), 2.0, 0.0);
        McpClientProperties.RetryPolicy effectful = new McpClientProperties.RetryPolicy(
                1, Duration.ofMillis(1), Duration.ofMillis(2), 2.0, 0.0);
        McpClientProperties.Server server = new McpClientProperties.Server(
                true, URI.create("http://repository-context-mcp:8091"), "repository-context-mcp", timeout,
                "repository-context-mcp", "0.1.0", Set.of("context.list_tree"));
        return new McpClientProperties(true, timeout, 65_536, 2, 1,
                Set.of("2025-06-18"), new McpClientProperties.Retry(readOnly, effectful),
                Map.of("repository-context", server));
    }

    @FunctionalInterface
    private interface Call {
        JsonNode invoke(String serverName, String toolName, Map<String, Object> arguments);
    }
}
