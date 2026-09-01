package com.example.aifactory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
public class SpringMcpToolInvoker implements McpToolInvoker {
    private final List<McpSyncClient> clients;
    private final ObjectMapper objectMapper;

    public SpringMcpToolInvoker(@Qualifier("mcpSyncClients") List<McpSyncClient> clients,
                                ObjectMapper objectMapper) {
        this.clients = clients;
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode call(String serverName, String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolResult result;
        try {
            result = client(serverName).callTool(new McpSchema.CallToolRequest(toolName, arguments));
        } catch (RuntimeException exception) {
            throw new McpInvocationException(
                    "DEPENDENCY_UNAVAILABLE", true, "MCP server is unavailable: " + serverName, exception);
        }
        if (Boolean.TRUE.equals(result.isError())) {
            throw new McpInvocationException("TOOL_ERROR", false,
                    "MCP tool failed: " + toolName + errorDetail(result));
        }
        if (result.structuredContent() != null) {
            return objectMapper.valueToTree(result.structuredContent());
        }
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent text) {
                try {
                    return objectMapper.readTree(text.text());
                } catch (Exception exception) {
                    throw new McpInvocationException(
                            "INCOMPATIBLE_SCHEMA", false, "MCP tool returned invalid JSON: " + toolName, exception);
                }
            }
        }
        throw new McpInvocationException(
                "INCOMPATIBLE_SCHEMA", false, "MCP tool returned no structured result: " + toolName);
    }

    private static String errorDetail(McpSchema.CallToolResult result) {
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent text && text.text() != null && !text.text().isBlank()) {
                String detail = text.text().replaceAll("[\\r\\n\\t]+", " ").strip();
                return " (" + detail.substring(0, Math.min(detail.length(), 300)) + ")";
            }
        }
        return "";
    }

    @Override
    public Availability availability(String serverName) {
        try {
            McpSyncClient client = client(serverName);
            return new Availability(client.isInitialized(), client.isInitialized() ? null : "MCP client is not initialized");
        } catch (RuntimeException exception) {
            return new Availability(false, exception.getMessage());
        }
    }

    @Override
    public ServerDescriptor describe(String serverName) {
        try {
            McpSyncClient client = client(serverName);
            if (!client.isInitialized()) {
                return new ServerDescriptor(false, null, serverName, null, Set.of(),
                        "MCP client is not initialized");
            }
            McpSchema.InitializeResult initialization = client.getCurrentInitializationResult();
            McpSchema.Implementation implementation = initialization.serverInfo();
            Set<String> tools = listToolNames(client);
            return new ServerDescriptor(
                    true,
                    initialization.protocolVersion(),
                    implementation.name(),
                    implementation.version(),
                    tools,
                    null);
        } catch (RuntimeException exception) {
            return new ServerDescriptor(false, null, serverName, null, Set.of(), safeMessage(exception));
        }
    }

    private Set<String> listToolNames(McpSyncClient client) {
        Set<String> names = new LinkedHashSet<>();
        String cursor = null;
        for (int page = 0; page < 16; page++) {
            McpSchema.ListToolsResult result = cursor == null ? client.listTools() : client.listTools(cursor);
            result.tools().forEach(tool -> names.add(tool.name()));
            cursor = result.nextCursor();
            if (cursor == null || cursor.isBlank()) {
                return Set.copyOf(names);
            }
        }
        throw new IllegalStateException("MCP tool catalogue exceeds 16 pages");
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private McpSyncClient client(String serverName) {
        return clients.stream()
                .filter(client -> client.getServerInfo() != null)
                .filter(client -> serverName.equals(client.getServerInfo().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("MCP client is unavailable: " + serverName));
    }
}
