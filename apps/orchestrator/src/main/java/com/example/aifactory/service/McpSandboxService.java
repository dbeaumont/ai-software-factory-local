package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class McpSandboxService implements SandboxExecutor {
    private static final int OUTPUT_PAGE_CHARS = 16_384;
    private static final int MAX_RETAINED_OUTPUT_CHARS = 1_048_576;
    private final McpToolInvoker invoker;
    private final McpFactoryProperties properties;
    private final Counter calls;
    private final Counter errors;
    private final Timer duration;

    public McpSandboxService(McpToolInvoker invoker, McpFactoryProperties properties, MeterRegistry metrics) {
        this.invoker = invoker;
        this.properties = properties;
        this.calls = Counter.builder("ai_factory_mcp_sandbox_calls").register(metrics);
        this.errors = Counter.builder("ai_factory_mcp_sandbox_errors").register(metrics);
        this.duration = Timer.builder("ai_factory_mcp_sandbox_duration").register(metrics);
    }

    @Override
    public String applyPatch(Path workspace, String taskId, String sourceCommit) throws Exception {
        return execute("sandbox.apply_patch", "apply-patch", workspace, taskId, sourceCommit);
    }

    @Override
    public String checkPatch(Path workspace, String taskId, String sourceCommit) throws Exception {
        return execute("sandbox.validate_patch", "validate-patch", workspace, taskId, sourceCommit);
    }

    @Override
    public String test(Path workspace, String taskId, String sourceCommit) throws Exception {
        return execute("sandbox.run_tests", "run-tests", workspace, taskId, sourceCommit);
    }

    @Override
    public String quality(Path workspace, String taskId, String sourceCommit) throws Exception {
        return execute("sandbox.run_quality", "run-quality", workspace, taskId, sourceCommit);
    }

    @Override
    public String security(Path workspace, String taskId, String sourceCommit) throws Exception {
        return execute("sandbox.run_security", "run-security", workspace, taskId, sourceCommit);
    }

    public Availability availability() {
        if (!properties.sandboxEnabled()) {
            return new Availability(false, "sandbox MCP is disabled");
        }
        try {
            McpToolInvoker.Availability availability = invoker.availability(properties.sandboxServerName());
            return new Availability(availability.available(), availability.error());
        } catch (RuntimeException exception) {
            return new Availability(false, exception.getMessage());
        }
    }

    private String execute(String tool, String operation, Path workspace, String taskId, String sourceCommit) throws Exception {
        if (!properties.sandboxEnabled()) {
            throw new IllegalStateException("sandbox MCP is disabled");
        }
        try {
            return duration.recordCallable(() -> executeTimed(tool, operation, workspace, taskId, sourceCommit));
        } catch (Exception exception) {
            errors.increment();
            throw exception;
        }
    }

    private String executeTimed(String tool, String operation, Path workspace, String taskId, String sourceCommit) throws Exception {
        McpRequestMetadata metadata = McpRequestMetadata.create(
                taskId, sourceCommit, "workflow", properties.sandboxPollTimeout());
        String patchDigest = patchDigest(workspace);
        Map<String, Object> start = metadata.arguments();
        String inputDigest = patchDigest == null ? digest(sourceCommit) : patchDigest;
        start.put("idempotency_key", idempotencyKey(
                taskId, start.get("attempt_id").toString(), operation, inputDigest));
        if (patchDigest != null) {
            start.put("patch_digest", patchDigest);
        }
        JsonNode accepted = invoker.call(properties.sandboxServerName(), tool, start);
        String executionId = requiredText(accepted, "execution_id");
        long deadline = System.nanoTime() + properties.sandboxPollTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode execution = invoker.call(properties.sandboxServerName(), "sandbox.get_execution",
                    lookup(metadata, executionId));
            String status = requiredText(execution, "status");
            if (status.equals("SUCCEEDED")) {
                calls.increment();
                String verdict = requiredText(execution, "verdict");
                String output = collectOutput(execution, metadata, executionId);
                validateEvidence(execution, output);
                if (!verdict.equals("PASSED")) {
                    throw new IllegalStateException("Sandbox " + operation + " rejected (exit="
                            + execution.path("exit_code").asText("unknown") + "):\n" + output);
                }
                return output;
            }
            if (status.equals("FAILED") || status.equals("TIMED_OUT") || status.equals("CANCELLED")) {
                throw new IllegalStateException("Sandbox " + operation + " ended with " + status + ": "
                        + execution.path("error").asText("no safe error detail"));
            }
            if (!status.equals("ACCEPTED") && !status.equals("RUNNING")) {
                throw new IllegalStateException("Malformed sandbox MCP response: unknown status " + status);
            }
            validateHeartbeat(execution, properties.sandboxPollTimeout());
            sleep(properties.sandboxPollInterval());
        }
        throw new IllegalStateException("Sandbox " + operation + " polling timed out");
    }

    private static Map<String, Object> lookup(McpRequestMetadata metadata, String executionId) {
        return lookup(metadata, executionId, 0);
    }

    private static Map<String, Object> lookup(McpRequestMetadata metadata, String executionId, int outputCursor) {
        Map<String, Object> arguments = metadata.arguments();
        arguments.put("execution_id", executionId);
        arguments.put("output_cursor", outputCursor);
        arguments.put("output_limit", OUTPUT_PAGE_CHARS);
        return arguments;
    }

    private String collectOutput(JsonNode firstPage, McpRequestMetadata metadata, String executionId) throws Exception {
        StringBuilder output = new StringBuilder();
        JsonNode page = firstPage;
        int expectedCursor = 0;
        Integer declaredTotal = page.has("output_total_chars") ? page.path("output_total_chars").asInt(-1) : null;
        while (true) {
            int cursor = page.has("output_cursor") ? page.path("output_cursor").asInt(-1) : expectedCursor;
            if (cursor != expectedCursor) {
                throw new IllegalStateException("Malformed sandbox MCP response: invalid output_cursor");
            }
            output.append(page.path("output").asText(""));
            if (output.length() > MAX_RETAINED_OUTPUT_CHARS) {
                throw new IllegalStateException("Malformed sandbox MCP response: output exceeds client limit");
            }
            JsonNode nextNode = page.path("next_output_cursor");
            if (!nextNode.isIntegralNumber()) {
                if (declaredTotal != null && declaredTotal != output.length()) {
                    throw new IllegalStateException("Malformed sandbox MCP response: incomplete paginated output");
                }
                return output.toString();
            }
            int next = nextNode.asInt(-1);
            if (next <= expectedCursor || next != output.length()) {
                throw new IllegalStateException("Malformed sandbox MCP response: invalid next_output_cursor");
            }
            expectedCursor = next;
            page = invoker.call(properties.sandboxServerName(), "sandbox.get_execution",
                    lookup(metadata, executionId, next));
            String status = requiredText(page, "status");
            if (!status.equals("SUCCEEDED")) {
                throw new IllegalStateException("Malformed sandbox MCP response: output changed state during pagination");
            }
        }
    }

    private static void validateHeartbeat(JsonNode execution, Duration maximumAge) {
        String value = requiredText(execution, "heartbeat_at");
        try {
            Instant heartbeat = Instant.parse(value);
            Instant now = Instant.now();
            if (heartbeat.isAfter(now.plus(Duration.ofMinutes(1)))) {
                throw new IllegalStateException("Malformed sandbox MCP response: heartbeat_at is in the future");
            }
            if (heartbeat.isBefore(now.minus(maximumAge))) {
                throw new IllegalStateException("Sandbox execution heartbeat is stale");
            }
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException("Malformed sandbox MCP response: invalid heartbeat_at", exception);
        }
    }

    private static void validateEvidence(JsonNode execution, String output) throws Exception {
        String evidenceStatus = requiredText(execution, "evidence_status");
        String expectedDigest = requiredText(execution, "output_digest");
        if (!expectedDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Malformed sandbox MCP response: invalid output_digest");
        }
        String actualDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(output.getBytes(StandardCharsets.UTF_8)));
        if (!actualDigest.equals(expectedDigest)) {
            throw new IllegalStateException("Malformed sandbox MCP response: output digest mismatch");
        }
        if (!evidenceStatus.equals("COMPLETE") || execution.path("output_truncated").asBoolean(true)) {
            throw new IllegalStateException("Sandbox execution returned partial evidence");
        }
    }

    private static String patchDigest(Path workspace) throws Exception {
        Path patch = workspace.resolve("changes.patch");
        return Files.isRegularFile(patch)
                ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(patch)))
                : null;
    }

    private static String idempotencyKey(String taskId, String attemptId, String operation, String inputDigest) {
        return taskId + ':' + attemptId + ':' + operation + ':' + inputDigest;
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalStateException("Malformed sandbox MCP response: missing " + field);
        }
        return value;
    }

    private static void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(Math.max(1, duration.toMillis()));
    }

    public record Availability(boolean available, String error) {
    }
}
