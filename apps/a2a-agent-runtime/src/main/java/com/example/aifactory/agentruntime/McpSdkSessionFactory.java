package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.RoleScopedAgentContext;
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
import java.util.Arrays;

/** Programmatic MCP SDK factory; no Spring-wide MCP registry or unrelated server connection is created. */
final class McpSdkSessionFactory implements RoleScopedMcpClient.SessionFactory {
    private final WebClient.Builder webClient;
    private final ObjectMapper mapper;
    private final RoleScopedAgentContext role;
    private final AgentMcpProperties properties;
    private final McpRoleTokenProvider tokens;

    McpSdkSessionFactory(WebClient.Builder webClient, ObjectMapper mapper,
                         RoleScopedAgentContext role, AgentMcpProperties properties) {
        this.webClient = webClient;
        this.mapper = mapper;
        this.role = role;
        this.properties = properties;
        this.tokens = new McpRoleTokenProvider(role, properties);
    }

    @Override
    public RoleScopedMcpClient.Session connect(String serverName, URI uri, Duration timeout) {
        WebClient.Builder scoped = webClient.clone().baseUrl(uri.toString());
        char[] token = tokens.acquire();
        if (properties.securityEnabled()) {
            try {
                scoped.defaultHeaders(headers -> {
                    headers.setBearerAuth(new String(token));
                    headers.set("X-AI-Factory-MCP-Role", role.identity().role());
                });
            } finally {
                Arrays.fill(token, '\0');
            }
        }
        var transport = WebClientStreamableHttpTransport.builder(
                        scoped)
                .endpoint("/mcp").resumableStreams(false).openConnectionOnStartup(false).build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation(
                        "ai-factory-a2a-agent-" + role.identity().role(), "0.1.0"))
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
            if (Boolean.TRUE.equals(result.isError())) return modelVisibleError(result, mapper);
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

    static String modelVisibleError(McpSchema.CallToolResult result, ObjectMapper mapper) {
        String message = "MCP tool rejected the call";
        if (result != null && result.content() != null) {
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent text && text.text() != null && !text.text().isBlank()) {
                    message = text.text().replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").strip();
                    if (message.length() > 1_000) message = message.substring(0, 1_000);
                    break;
                }
            }
        }
        try {
            return mapper.writeValueAsString(Map.of("is_error", true, "message", message));
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot serialize bounded MCP tool error", failure);
        }
    }
}
