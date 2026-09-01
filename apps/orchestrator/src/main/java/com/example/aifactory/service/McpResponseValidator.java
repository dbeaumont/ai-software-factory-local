package com.example.aifactory.service;

import com.example.aifactory.config.McpClientProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class McpResponseValidator {
    private static final Map<String, String> SCHEMA_RESOURCES = Map.ofEntries(
            Map.entry("context.list_tree", "context-list-tree-runtime-v1.schema.json"),
            Map.entry("context.read_file", "context-read-file-runtime-v1.schema.json"),
            Map.entry("context.search_code", "context-search-code-runtime-v1.schema.json"),
            Map.entry("context.get_repository_rules", "context-repository-rules-runtime-v1.schema.json"),
            Map.entry("context.get_dependencies", "context-get-dependencies-runtime-v1.schema.json"),
            Map.entry("context.get_symbols", "context-get-symbols-runtime-v1.schema.json"),
            Map.entry("sandbox.validate_patch", "sandbox-execution-result-v1.schema.json"),
            Map.entry("sandbox.apply_patch", "sandbox-execution-result-v1.schema.json"),
            Map.entry("sandbox.run_tests", "sandbox-execution-result-v1.schema.json"),
            Map.entry("sandbox.run_quality", "sandbox-execution-result-v1.schema.json"),
            Map.entry("sandbox.run_security", "sandbox-execution-result-v1.schema.json"),
            Map.entry("sandbox.get_execution", "sandbox-execution-result-v1.schema.json"),
            Map.entry("sandbox.cancel_execution", "sandbox-execution-result-v1.schema.json"),
            Map.entry("scm.create_draft_pull_request", "scm-delivery-result-v1.schema.json"));

    private final ObjectMapper objectMapper;
    private final int maxResponseBytes;
    private final Map<String, Schema> schemas;

    public McpResponseValidator(ObjectMapper objectMapper, McpClientProperties properties) {
        this.objectMapper = objectMapper;
        this.maxResponseBytes = properties.maxResponseBytes();
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        Map<String, Schema> loaded = new LinkedHashMap<>();
        SCHEMA_RESOURCES.forEach((tool, resource) -> loaded.put(tool, load(registry, resource)));
        this.schemas = Map.copyOf(loaded);
    }

    public JsonNode validate(String toolName, JsonNode response) {
        if (response == null) {
            throw new McpResponseValidationException(toolName, "response is null");
        }
        int bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(response).length;
        } catch (Exception exception) {
            throw new McpResponseValidationException(toolName, "response cannot be serialized", exception);
        }
        if (bytes > maxResponseBytes) {
            throw new McpResponseValidationException(toolName, "response exceeds the configured byte limit");
        }
        Schema schema = schemas.get(toolName);
        if (schema == null) {
            throw new McpResponseValidationException(toolName, "no local response schema is registered");
        }
        List<Error> errors = schema.validate(response);
        if (!errors.isEmpty()) {
            String keyword = errors.getFirst().getKeyword();
            throw new McpResponseValidationException(toolName,
                    "response violates the local schema (" + errors.size() + " error(s), first keyword: "
                            + (keyword == null ? "unknown" : keyword) + ")");
        }
        return response;
    }

    private Schema load(SchemaRegistry registry, String resource) {
        String path = "mcp/schemas/" + resource;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing MCP response schema: " + path);
            }
            return registry.getSchema(input);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load MCP response schema: " + path, exception);
        }
    }

    public static final class McpResponseValidationException extends IllegalStateException {
        public McpResponseValidationException(String toolName, String reason) {
            super("Invalid MCP response for " + toolName + ": " + reason);
        }

        public McpResponseValidationException(String toolName, String reason, Throwable cause) {
            super("Invalid MCP response for " + toolName + ": " + reason, cause);
        }
    }
}
