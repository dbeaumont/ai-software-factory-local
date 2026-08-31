package com.example.aifactory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class SpringMcpToolInvoker implements McpToolInvoker {
    private final ObjectProvider<McpSyncClient> clients;
    private final ObjectMapper objectMapper;

    public SpringMcpToolInvoker(ObjectProvider<McpSyncClient> clients, ObjectMapper objectMapper) {
        this.clients = clients;
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode call(String serverName, String toolName, Map<String, Object> arguments) {
        McpSchema.CallToolResult result = client(serverName)
                .callTool(new McpSchema.CallToolRequest(toolName, arguments));
        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("MCP tool failed: " + toolName);
        }
        if (result.structuredContent() != null) {
            return objectMapper.valueToTree(result.structuredContent());
        }
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent text) {
                try {
                    return objectMapper.readTree(text.text());
                } catch (Exception exception) {
                    throw new IllegalStateException("MCP tool returned invalid JSON: " + toolName, exception);
                }
            }
        }
        throw new IllegalStateException("MCP tool returned no structured result: " + toolName);
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

    private McpSyncClient client(String serverName) {
        return clients.orderedStream()
                .filter(client -> client.getServerInfo() != null)
                .filter(client -> serverName.equals(client.getServerInfo().name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("MCP client is unavailable: " + serverName));
    }
}

