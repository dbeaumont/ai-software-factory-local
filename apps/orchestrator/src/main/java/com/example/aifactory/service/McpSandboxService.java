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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class McpSandboxService implements SandboxExecutor {
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
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String patchDigest = patchDigest(workspace);
        Map<String, Object> start = common(taskId, sourceCommit, traceId);
        start.put("idempotency_key", idempotencyKey(taskId, sourceCommit, operation, patchDigest));
        if (patchDigest != null) {
            start.put("patch_digest", patchDigest);
        }
        JsonNode accepted = invoker.call(properties.sandboxServerName(), tool, start);
        String executionId = requiredText(accepted, "execution_id");
        long deadline = System.nanoTime() + properties.sandboxPollTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode execution = invoker.call(properties.sandboxServerName(), "sandbox.get_execution",
                    lookup(taskId, sourceCommit, traceId, executionId));
            String status = requiredText(execution, "status");
            if (status.equals("SUCCEEDED")) {
                calls.increment();
                String verdict = requiredText(execution, "verdict");
                String output = execution.path("output").asText("");
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
            sleep(properties.sandboxPollInterval());
        }
        throw new IllegalStateException("Sandbox " + operation + " polling timed out");
    }

    private static Map<String, Object> common(String taskId, String sourceCommit, String traceId) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("schema_version", "1");
        arguments.put("task_id", taskId);
        arguments.put("source_commit", sourceCommit);
        arguments.put("actor", "workflow");
        arguments.put("trace_id", traceId);
        return arguments;
    }

    private static Map<String, Object> lookup(String taskId, String sourceCommit, String traceId, String executionId) {
        Map<String, Object> arguments = common(taskId, sourceCommit, traceId);
        arguments.put("execution_id", executionId);
        return arguments;
    }

    private static String patchDigest(Path workspace) throws Exception {
        Path patch = workspace.resolve("changes.patch");
        return Files.isRegularFile(patch)
                ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(patch)))
                : null;
    }

    private static String idempotencyKey(String taskId, String sourceCommit, String operation, String patchDigest) throws Exception {
        String material = taskId + ':' + sourceCommit + ':' + operation + ':' + (patchDigest == null ? "none" : patchDigest);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8)));
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
