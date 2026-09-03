package com.example.aifactory.service;

import com.example.aifactory.config.McpClientProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Primary
public class ResilientMcpToolInvoker implements McpToolInvoker {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResilientMcpToolInvoker.class);
    private static final Set<String> EFFECTFUL_TOOLS = Set.of(
            "sandbox.validate_patch", "sandbox.apply_patch", "sandbox.run_tests", "sandbox.run_quality",
            "sandbox.run_security", "sandbox.cancel_execution", "assurance.evaluate_quality_gate",
            "assurance.normalize_findings", "assurance.evaluate_policy", "evidence.store",
            "evidence.create_manifest", "scm.create_draft_pull_request");
    private static final Set<String> FINAL_GATE_TOOLS = Set.of(
            "assurance.evaluate_policy", "evidence.create_manifest", "scm.create_draft_pull_request");
    private static final int CIRCUIT_FAILURE_THRESHOLD = 5;
    private static final Duration CIRCUIT_OPEN_DURATION = Duration.ofSeconds(30);

    private final McpToolInvoker delegate;
    private final McpClientProperties properties;
    private final MeterRegistry metrics;
    private final ObjectMapper objectMapper;
    private final TaskUsageLedger usage;
    private final ExecutionTracer tracer;
    private final WorkflowOperationalMetrics operationalMetrics;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore globalLimit;
    private final ConcurrentMap<String, Semaphore> serverLimits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Semaphore> taskLimits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Semaphore> roleLimits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Circuit> circuits = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> inflight = new ConcurrentHashMap<>();

    public ResilientMcpToolInvoker(
            @Qualifier("validatedMcpToolInvoker") McpToolInvoker delegate,
            McpClientProperties properties,
            MeterRegistry metrics,
            ObjectMapper objectMapper) {
        this(delegate, properties, metrics, objectMapper,
                new TaskUsageLedger(new HierarchicalBudgetPolicy()));
    }

    public ResilientMcpToolInvoker(
            @Qualifier("validatedMcpToolInvoker") McpToolInvoker delegate,
            McpClientProperties properties,
            MeterRegistry metrics,
            ObjectMapper objectMapper,
            TaskUsageLedger usage) {
        this(delegate, properties, metrics, objectMapper, usage, ExecutionTracer.noop());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ResilientMcpToolInvoker(
            @Qualifier("validatedMcpToolInvoker") McpToolInvoker delegate,
            McpClientProperties properties,
            MeterRegistry metrics,
            ObjectMapper objectMapper,
            TaskUsageLedger usage,
            ExecutionTracer tracer) {
        this.delegate = delegate;
        this.properties = properties;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.usage = usage;
        this.tracer = tracer;
        this.operationalMetrics = new WorkflowOperationalMetrics(metrics);
        this.globalLimit = new Semaphore(properties.maxInflightGlobal(), true);
        properties.servers().values().forEach(server -> {
            serverLimits.put(server.expectedName(), new Semaphore(properties.maxInflightPerServer(), true));
            circuits.put(server.expectedName(), new Circuit());
            AtomicInteger current = new AtomicInteger();
            inflight.put(server.expectedName(), current);
            Gauge.builder("mcp_client_inflight", current, AtomicInteger::get)
                    .tag("server", server.expectedName()).register(metrics);
        });
    }

    @Override
    public JsonNode call(String serverName, String toolName, Map<String, Object> arguments) {
        ExecutionIdentity identity = identity(arguments);
        return tracer.trace(ExecutionTracer.SpanKind.MCP, identity, serverName + '.' + toolName,
                () -> callObserved(serverName, toolName, arguments));
    }

    private JsonNode callObserved(String serverName, String toolName, Map<String, Object> arguments) {
        Circuit circuit = circuit(serverName);
        if (!circuit.allow()) {
            recordError(serverName, toolName, "CIRCUIT_OPEN");
            throw new McpInvocationException("CIRCUIT_OPEN", false, "MCP circuit is open: " + serverName);
        }
        McpClientProperties.RetryPolicy retry = policy(toolName);
        int attempts = EFFECTFUL_TOOLS.contains(toolName) && !arguments.containsKey("idempotency_key")
                ? 1 : retry.maxAttempts();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            long started = System.nanoTime();
            try {
                accountMcp(toolName, arguments);
                JsonNode response = timedCall(serverName, toolName, arguments);
                circuit.success();
                recordSuccess(serverName, toolName, arguments, response, started);
                return response;
            } catch (RuntimeException exception) {
                last = exception;
                String code = errorCode(exception);
                recordFailure(serverName, toolName, arguments, code, started);
                if (!retryable(exception) || attempt == attempts || deadlineExpired(arguments)) {
                    if (retryable(exception)) {
                        circuit.failure();
                    }
                    throw exception;
                }
                Counter.builder("mcp_client_retries").tag("server", serverName).tag("tool", toolName)
                        .register(metrics).increment();
                operationalMetrics.retry("mcp");
                sleep(backoff(retry, attempt));
            }
        }
        throw last == null ? new IllegalStateException("MCP call failed") : last;
    }

    private ExecutionIdentity identity(Map<String, Object> arguments) {
        Object trace = arguments.get("trace_id");
        Object run = arguments.get("run_id");
        Object delegation = arguments.get("delegation_id");
        Object agentRun = arguments.get("agent_run_id");
        if (trace != null && run != null && delegation != null && agentRun != null) {
            return new ExecutionIdentity(trace.toString(), run.toString(), delegation.toString(), agentRun.toString());
        }
        String taskId = safe(arguments.get("task_id"));
        String attemptId = safe(arguments.get("attempt_id"));
        String actor = safe(arguments.get("actor"));
        return ExecutionIdentity.deterministic(validSeed(taskId) ? taskId : "unknown",
                validSeed(attemptId) ? attemptId : "unknown", validSeed(actor) ? actor : "unknown",
                validSeed(actor) ? actor : "unknown");
    }

    private static boolean validSeed(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    private void accountMcp(String toolName, Map<String, Object> arguments) {
        // Sandbox status polling is host-controlled and bounded by the sandbox polling timeout.
        // It must not exhaust the task budget reserved for agent work.
        if ("sandbox.get_execution".equals(toolName)) {
            return;
        }
        String actor = String.valueOf(arguments.getOrDefault("actor", "unknown"));
        TaskUsageLedger.Lane lane = "independent-reviewer".equals(actor) || FINAL_GATE_TOOLS.contains(toolName)
                ? TaskUsageLedger.Lane.FINALIZATION : TaskUsageLedger.Lane.STANDARD;
        usage.consume(String.valueOf(arguments.getOrDefault("task_id", "unknown")),
                String.valueOf(arguments.getOrDefault("attempt_id", "unknown")), lane,
                new TaskUsageLedger.Delta(0, 0, 0, 0, 1));
    }

    private JsonNode timedCall(String serverName, String toolName, Map<String, Object> arguments) {
        Duration timeout = timeout(serverName, arguments);
        long acquireDeadline = System.nanoTime() + timeout.toNanos();
        Semaphore server = serverLimits.computeIfAbsent(
                serverName, ignored -> new Semaphore(properties.maxInflightPerServer(), true));
        String taskKey = safe(arguments.get("task_id"));
        Semaphore task = taskLimits.computeIfAbsent(
                taskKey, ignored -> new Semaphore(properties.maxInflightPerTask(), true));
        String roleKey = safe(arguments.get("actor"));
        Semaphore role = roleLimits.computeIfAbsent(
                roleKey, ignored -> new Semaphore(properties.maxInflightPerRole(), true));
        boolean globalAcquired = false;
        boolean serverAcquired = false;
        boolean taskAcquired = false;
        boolean roleAcquired = false;
        try {
            globalAcquired = acquire(globalLimit, acquireDeadline);
            serverAcquired = globalAcquired && acquire(server, acquireDeadline);
            taskAcquired = serverAcquired && acquire(task, acquireDeadline);
            roleAcquired = taskAcquired && acquire(role, acquireDeadline);
            if (!roleAcquired) {
                throw new McpInvocationException("LIMIT_EXCEEDED", false, "MCP concurrency limit exceeded");
            }
            inflight.computeIfAbsent(serverName, ignored -> new AtomicInteger()).incrementAndGet();
            Future<JsonNode> future;
            try {
                future = executor.submit(() -> {
                    try {
                        return delegate.call(serverName, toolName, arguments);
                    } finally {
                        role.release();
                        task.release();
                        server.release();
                        globalLimit.release();
                        inflight.get(serverName).decrementAndGet();
                    }
                });
            } catch (RuntimeException rejected) {
                inflight.get(serverName).decrementAndGet();
                throw rejected;
            }
            roleAcquired = false;
            taskAcquired = false;
            serverAcquired = false;
            globalAcquired = false;
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                future.cancel(true);
                throw new McpInvocationException("TIMEOUT", true, "MCP call timed out", exception);
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new McpInvocationException("DEPENDENCY_UNAVAILABLE", true, "MCP call failed", cause);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new McpInvocationException("INTERRUPTED", false, "MCP call was interrupted", exception);
        } finally {
            if (roleAcquired) role.release();
            if (taskAcquired) task.release();
            if (serverAcquired) server.release();
            if (globalAcquired) globalLimit.release();
        }
    }

    private static boolean acquire(Semaphore semaphore, long deadlineNanos) throws InterruptedException {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining > 0 && semaphore.tryAcquire(remaining, TimeUnit.NANOSECONDS);
    }

    private Duration timeout(String serverName, Map<String, Object> arguments) {
        Duration configured = properties.servers().values().stream()
                .filter(server -> server.expectedName().equals(serverName))
                .map(McpClientProperties.Server::requestTimeout)
                .findFirst().orElse(properties.requestTimeout());
        Object deadlineValue = arguments.get("deadline");
        if (deadlineValue == null) {
            return configured;
        }
        try {
            Duration remaining = Duration.between(Instant.now(), Instant.parse(deadlineValue.toString()));
            if (remaining.isNegative() || remaining.isZero()) {
                throw new McpInvocationException("TIMEOUT", false, "MCP deadline has expired");
            }
            return remaining.compareTo(configured) < 0 ? remaining : configured;
        } catch (McpInvocationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new McpInvocationException("INVALID_ARGUMENT", false, "Invalid MCP deadline", exception);
        }
    }

    private McpClientProperties.RetryPolicy policy(String toolName) {
        return EFFECTFUL_TOOLS.contains(toolName) ? properties.retry().effectful() : properties.retry().readOnly();
    }

    private Duration backoff(McpClientProperties.RetryPolicy policy, int failedAttempt) {
        double exponential = policy.initialBackoff().toMillis()
                * Math.pow(policy.multiplier(), Math.max(0, failedAttempt - 1));
        double bounded = Math.min(exponential, policy.maxBackoff().toMillis());
        double jitter = policy.jitter() == 0.0
                ? 0.0
                : ThreadLocalRandom.current().nextDouble(-policy.jitter(), policy.jitter());
        return Duration.ofMillis(Math.max(1, Math.round(bounded * (1.0 + jitter))));
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new McpInvocationException("INTERRUPTED", false, "MCP retry was interrupted", exception);
        }
    }

    private boolean retryable(RuntimeException exception) {
        return exception instanceof McpInvocationException invocation && invocation.retryable();
    }

    private boolean deadlineExpired(Map<String, Object> arguments) {
        Object value = arguments.get("deadline");
        try {
            return value != null && !Instant.parse(value.toString()).isAfter(Instant.now());
        } catch (RuntimeException exception) {
            return true;
        }
    }

    private String errorCode(RuntimeException exception) {
        if (exception instanceof McpInvocationException invocation) {
            return invocation.code();
        }
        if (exception instanceof McpResponseValidator.McpResponseValidationException) {
            return "INCOMPATIBLE_SCHEMA";
        }
        return "CLIENT_ERROR";
    }

    private void recordSuccess(String server, String tool, Map<String, Object> arguments, JsonNode response,
                               long started) {
        long durationNanos = System.nanoTime() - started;
        byte[] bytes = serialize(response);
        recordCall(server, tool, "success", durationNanos);
        LOGGER.debug("MCP call server={} tool={} task={} attempt={} actor={} duration_ms={} response_bytes={} response_digest={}",
                server, tool, safe(arguments.get("task_id")), safe(arguments.get("attempt_id")),
                safe(arguments.get("actor")), TimeUnit.NANOSECONDS.toMillis(durationNanos), bytes.length, digest(bytes));
    }

    private void recordFailure(String server, String tool, Map<String, Object> arguments, String code, long started) {
        long durationNanos = System.nanoTime() - started;
        recordCall(server, tool, "error", durationNanos);
        recordError(server, tool, code);
        LOGGER.warn("MCP call server={} tool={} task={} attempt={} actor={} duration_ms={} error_code={}",
                server, tool, safe(arguments.get("task_id")), safe(arguments.get("attempt_id")),
                safe(arguments.get("actor")), TimeUnit.NANOSECONDS.toMillis(durationNanos), code);
    }

    private void recordCall(String server, String tool, String outcome, long durationNanos) {
        Counter.builder("mcp_client_calls").tag("server", server).tag("tool", tool).tag("outcome", outcome)
                .register(metrics).increment();
        Timer.builder("mcp_client_duration").tag("server", server).tag("tool", tool).tag("outcome", outcome)
                .register(metrics).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    private void recordError(String server, String tool, String code) {
        Counter.builder("mcp_client_errors").tag("server", server).tag("tool", tool).tag("code", code)
                .register(metrics).increment();
    }

    private byte[] serialize(JsonNode response) {
        try {
            return objectMapper.writeValueAsBytes(response);
        } catch (Exception exception) {
            return new byte[0];
        }
    }

    private String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            return "unavailable";
        }
    }

    private String safe(Object value) {
        if (value == null) {
            return "none";
        }
        String text = value.toString();
        return text.matches("[A-Za-z0-9_-]{1,64}") ? text : "invalid";
    }

    private Circuit circuit(String serverName) {
        return circuits.computeIfAbsent(serverName, ignored -> new Circuit());
    }

    @Override
    public Availability availability(String serverName) {
        if (!circuit(serverName).allow()) {
            return new Availability(false, "MCP circuit is open");
        }
        return delegate.availability(serverName);
    }

    @Override
    public ServerDescriptor describe(String serverName) {
        return delegate.describe(serverName);
    }

    @PreDestroy
    void close() {
        executor.close();
    }

    private static final class Circuit {
        private final AtomicInteger failures = new AtomicInteger();
        private volatile long openUntil;

        private boolean allow() {
            return System.nanoTime() >= openUntil;
        }

        private void success() {
            failures.set(0);
            openUntil = 0;
        }

        private void failure() {
            if (failures.incrementAndGet() >= CIRCUIT_FAILURE_THRESHOLD) {
                openUntil = System.nanoTime() + CIRCUIT_OPEN_DURATION.toNanos();
                failures.set(0);
            }
        }
    }
}
