package com.example.aifactory.service;

import com.example.aifactory.config.McpClientProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    @Test
    void enforcesGlobalServerTaskAndRoleConcurrencyIndependently() throws Exception {
        assertConcurrencyLimit(new ConcurrencyCase(1, 1, 1, 1,
                "server-a", "task-a", "developer", "server-b", "task-b", "test-design"));
        assertConcurrencyLimit(new ConcurrencyCase(4, 1, 1, 1,
                "server-a", "task-a", "developer", "server-a", "task-b", "test-design"));
        assertConcurrencyLimit(new ConcurrencyCase(4, 4, 1, 4,
                "server-a", "task-a", "developer", "server-b", "task-a", "test-design"));
        assertConcurrencyLimit(new ConcurrencyCase(4, 4, 4, 1,
                "server-a", "task-a", "developer", "server-b", "task-b", "developer"));
    }

    private void assertConcurrencyLimit(ConcurrencyCase limits) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        McpToolInvoker delegate = delegate((server, tool, arguments) -> {
            entered.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return new ObjectMapper().createObjectNode().put("ok", true);
        });
        invoker = new ResilientMcpToolInvoker(delegate,
                properties(Duration.ofSeconds(1), limits.global(), limits.server(), limits.task(), limits.role()),
                new SimpleMeterRegistry(), new ObjectMapper());
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<JsonNode> first = callers.submit(() -> invoker.call(limits.firstServer(), "context.list_tree",
                    arguments(limits.firstTask(), limits.firstRole())));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> invoker.call(limits.secondServer(), "context.list_tree",
                    arguments(limits.secondTask(), limits.secondRole(), Duration.ofMillis(40))))
                    .isInstanceOf(McpInvocationException.class)
                    .extracting(error -> ((McpInvocationException) error).code())
                    .isEqualTo("LIMIT_EXCEEDED");
            release.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS).path("ok").asBoolean()).isTrue();
        } finally {
            release.countDown();
            invoker.close();
            invoker = null;
        }
    }

    private static Map<String, Object> arguments() {
        return arguments("task-1", "workflow");
    }

    private static Map<String, Object> arguments(String task, String role) {
        return arguments(task, role, Duration.ofSeconds(5));
    }

    private static Map<String, Object> arguments(String task, String role, Duration remaining) {
        return Map.of(
                "task_id", task,
                "attempt_id", "attempt-1",
                "actor", role,
                "deadline", Instant.now().plus(remaining).toString());
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

    private static McpClientProperties properties(Duration timeout, int global, int serverLimit,
                                                   int task, int role) {
        McpClientProperties.RetryPolicy retry = new McpClientProperties.RetryPolicy(
                1, Duration.ofMillis(1), Duration.ofMillis(2), 2.0, 0.0);
        McpClientProperties.Server server = new McpClientProperties.Server(
                true, URI.create("http://repository-context-mcp:8091"), "repository-context-mcp", timeout,
                "repository-context-mcp", "0.1.0", Set.of("context.list_tree"));
        return new McpClientProperties(true, timeout, 65_536, global, serverLimit, task, role,
                Set.of("2025-06-18"), new McpClientProperties.Retry(retry, retry),
                Map.of("repository-context", server));
    }

    private record ConcurrencyCase(int global, int server, int task, int role,
                                   String firstServer, String firstTask, String firstRole,
                                   String secondServer, String secondTask, String secondRole) { }

    @FunctionalInterface
    private interface Call {
        JsonNode invoke(String serverName, String toolName, Map<String, Object> arguments);
    }
}
