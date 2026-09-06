package com.example.aifactory.agentruntime;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.mcp.client.webflux.transport.WebClientStreamableHttpTransport;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Programmatic MCP SDK factory; no Spring-wide MCP registry or unrelated server connection is created. */
final class McpSdkSessionFactory implements RoleScopedMcpClient.SessionFactory {
    private final WebClient.Builder webClient;
    private final ObjectMapper mapper;

    McpSdkSessionFactory(WebClient.Builder webClient, ObjectMapper mapper) {
        this.webClient = webClient;
        this.mapper = mapper;
    }

    @Override
    public RoleScopedMcpClient.Session connect(String serverName, URI uri, Duration timeout) {
        var transport = WebClientStreamableHttpTransport.builder(
                        webClient.clone().baseUrl(uri.toString()))
                .endpoint("/mcp").resumableStreams(false).openConnectionOnStartup(false).build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("ai-factory-a2a-agent-runtime", "0.1.0"))
                .initializationTimeout(timeout).requestTimeout(timeout).build();
        client.initialize();
        if (client.getServerInfo() == null || !serverName.equals(client.getServerInfo().name())) {
            client.close();
            throw new IllegalStateException("Unexpected MCP server identity for " + serverName);
        }
        Set<String> tools = new LinkedHashSet<>();
        String cursor = null;
        for (int page = 0; page < 16; page++) {
            McpSchema.ListToolsResult result = cursor == null ? client.listTools() : client.listTools(cursor);
            result.tools().forEach(tool -> tools.add(tool.name()));
            cursor = result.nextCursor();
            if (cursor == null || cursor.isBlank()) return new SdkSession(client, Set.copyOf(tools), mapper);
        }
        client.close();
        throw new IllegalStateException("MCP tool catalogue exceeds 16 pages");
    }

    private record SdkSession(McpSyncClient client, Set<String> tools,
                              ObjectMapper mapper) implements RoleScopedMcpClient.Session {
        @Override public String call(String toolName, Map<String, Object> arguments) {
            McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(toolName, arguments));
            if (Boolean.TRUE.equals(result.isError())) throw new IllegalStateException("MCP tool returned an error");
            try {
                if (result.structuredContent() != null) return mapper.writeValueAsString(result.structuredContent());
                for (McpSchema.Content content : result.content()) {
                    if (content instanceof McpSchema.TextContent text) {
                        mapper.readTree(text.text());
                        return text.text();
                    }
                }
            } catch (Exception exception) {
                throw new IllegalStateException("MCP tool returned invalid JSON", exception);
            }
            throw new IllegalStateException("MCP tool returned no structured JSON");
        }
        @Override public void close() { client.close(); }
    }
}
