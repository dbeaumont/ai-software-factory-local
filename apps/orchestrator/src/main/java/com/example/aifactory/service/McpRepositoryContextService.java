package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;

@Service
public class McpRepositoryContextService implements McpContextProvider {
    private static final Set<String> ACCEPTED_NAMES = Set.of("pom.xml", "Dockerfile", "Makefile");
    private static final Set<String> ACCEPTED_EXTENSIONS = Set.of(
            ".java", ".kt", ".xml", ".yml", ".yaml", ".json", ".ts", ".js", ".properties", ".gradle", ".md");
    private static final int MAX_CONTEXT_CHARS = 40_000;
    private static final int MAX_FILES = 80;

    private final McpToolInvoker invoker;
    private final McpFactoryProperties properties;
    private final Counter calls;
    private final Counter errors;
    private final Timer duration;

    public McpRepositoryContextService(McpToolInvoker invoker,
                                       McpFactoryProperties properties,
                                       MeterRegistry registry) {
        this.invoker = invoker;
        this.properties = properties;
        this.calls = Counter.builder("ai_factory_mcp_client_calls")
                .tag("server", "repository-context-mcp")
                .register(registry);
        this.errors = Counter.builder("ai_factory_mcp_client_errors")
                .tag("server", "repository-context-mcp")
                .register(registry);
        this.duration = Timer.builder("ai_factory_mcp_client_duration")
                .tag("server", "repository-context-mcp")
                .register(registry);
    }

    @Override
    public String collect(Path repository, String taskId, String sourceCommit) {
        if (!properties.enabled()) {
            throw new IllegalStateException("MCP is disabled");
        }
        return duration.record(() -> {
            try {
                String traceId = UUID.randomUUID().toString().replace("-", "");
                Map<String, Object> common = commonArguments(taskId, sourceCommit, traceId);
                Map<String, Object> listArguments = new LinkedHashMap<>(common);
                listArguments.put("path", "");
                listArguments.put("depth", 12);
                listArguments.put("max_entries", 500);
                JsonNode tree = invoker.call(properties.repositoryContextServerName(), "context.list_tree", listArguments);
                StringBuilder context = new StringBuilder();
                int files = 0;
                for (JsonNode entry : tree.path("entries")) {
                    if (files >= MAX_FILES || context.length() >= MAX_CONTEXT_CHARS) {
                        break;
                    }
                    String path = entry.path("path").asText();
                    if (!"file".equals(entry.path("type").asText()) || !accepted(path)) {
                        continue;
                    }
                    Map<String, Object> readArguments = new LinkedHashMap<>(common);
                    readArguments.put("path", path);
                    readArguments.put("start_line", 1);
                    readArguments.put("max_bytes", 6_000);
                    JsonNode file = invoker.call(properties.repositoryContextServerName(), "context.read_file", readArguments);
                    context.append("\n--- FILE: ").append(path).append(" ---\n")
                            .append(file.path("content").asText()).append('\n');
                    files++;
                }
                calls.increment();
                return context.substring(0, Math.min(context.length(), MAX_CONTEXT_CHARS));
            } catch (RuntimeException exception) {
                errors.increment();
                throw exception;
            }
        });
    }

    public Availability availability() {
        if (!properties.enabled()) {
            return new Availability(false, "MCP is disabled");
        }
        try {
            McpToolInvoker.Availability availability = invoker.availability(properties.repositoryContextServerName());
            return new Availability(availability.available(), availability.error());
        } catch (RuntimeException exception) {
            return new Availability(false, exception.getMessage());
        }
    }

    private static Map<String, Object> commonArguments(String taskId, String sourceCommit, String traceId) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("schema_version", "1");
        arguments.put("task_id", taskId);
        arguments.put("source_commit", sourceCommit);
        arguments.put("actor", "workflow");
        arguments.put("trace_id", traceId);
        return arguments;
    }

    private static boolean accepted(String path) {
        String name = Path.of(path).getFileName().toString();
        return ACCEPTED_NAMES.contains(name) || ACCEPTED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    public record Availability(boolean available, String error) {
    }
}
