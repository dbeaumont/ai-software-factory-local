package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import tools.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class McpRepositoryContextService implements McpContextProvider {
    private static final Set<String> ACCEPTED_NAMES = Set.of("pom.xml", "Dockerfile", "Makefile");
    private static final Set<String> ACCEPTED_EXTENSIONS = Set.of(
            ".java", ".kt", ".xml", ".yml", ".yaml", ".json", ".ts", ".js", ".properties", ".gradle", ".md");
    private static final int MAX_CONTEXT_CHARS = 40_000;
    private static final int MAX_FILES = 80;
    private static final int MAX_TREE_PAGES = 10;

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
                McpRequestMetadata metadata = McpRequestMetadata.create(
                        taskId, sourceCommit, "workflow", java.time.Duration.ofSeconds(20));
                Map<String, Object> common = metadata.arguments();
                Map<String, Object> listArguments = new LinkedHashMap<>(common);
                listArguments.put("path", "");
                listArguments.put("depth", 12);
                listArguments.put("max_entries", 500);
                StringBuilder context = new StringBuilder();
                int files = 0;
                String cursor = null;
                for (int page = 0; page < MAX_TREE_PAGES
                        && files < MAX_FILES && context.length() < MAX_CONTEXT_CHARS; page++) {
                    Map<String, Object> pageArguments = new LinkedHashMap<>(listArguments);
                    if (cursor != null) {
                        pageArguments.put("cursor", cursor);
                    }
                    JsonNode tree = invoker.call(properties.repositoryContextServerName(), "context.list_tree",
                            pageArguments);
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
                        JsonNode file = invoker.call(properties.repositoryContextServerName(), "context.read_file",
                                readArguments);
                        context.append("\n--- FILE: ").append(path).append(" ---\n")
                                .append("SOURCE: repo://").append(taskId).append('/').append(sourceCommit).append('/')
                                .append(URLEncoder.encode(path, StandardCharsets.UTF_8))
                                .append("#sha256=").append(file.path("sha256").asText()).append('\n')
                                .append(file.path("content").asText()).append('\n');
                        files++;
                    }
                    cursor = tree.path("next_cursor").asText(null);
                    if (cursor == null || cursor.isBlank()) {
                        break;
                    }
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

    private static boolean accepted(String path) {
        String name = Path.of(path).getFileName().toString();
        return ACCEPTED_NAMES.contains(name) || ACCEPTED_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    public record Availability(boolean available, String error) {
    }
}
