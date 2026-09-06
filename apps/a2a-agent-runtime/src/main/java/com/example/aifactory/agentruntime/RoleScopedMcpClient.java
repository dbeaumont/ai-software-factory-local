package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.LlmCompletionPort;
import com.example.aifactory.agentcore.McpToolPort;
import com.example.aifactory.agentcore.RoleScopedAgentContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Creates only MCP connections required by the selected role and rechecks grants on every call. */
final class RoleScopedMcpClient implements McpToolPort, AutoCloseable {
    private static final Map<String, LlmCompletionPort.ToolDefinition> DEFINITIONS = definitionsByName();
    private final RoleScopedAgentContext role;
    private final boolean enabled;
    private final Duration timeout;
    private final SessionFactory sessions;
    private final Map<String, URI> serverUris;
    private final Map<String, Session> connected = new ConcurrentHashMap<>();

    RoleScopedMcpClient(RoleScopedAgentContext role, AgentMcpProperties properties, SessionFactory sessions) {
        this.role = role;
        this.enabled = properties.enabled();
        this.timeout = properties.requestTimeout();
        this.sessions = sessions;
        Map<String, URI> required = new LinkedHashMap<>();
        for (String tool : role.allowedTools()) {
            if (tool.startsWith("context.")) required.put("repository-context-mcp", properties.repositoryContextUrl());
            else if (tool.startsWith("evidence.")) required.put("evidence-mcp", properties.evidenceUrl());
            else throw new IllegalStateException("Agent role contains an unsupported MCP tool namespace: " + tool);
            if (!DEFINITIONS.containsKey(tool)) throw new IllegalStateException("Missing local schema for " + tool);
        }
        this.serverUris = Map.copyOf(required);
    }

    Set<String> configuredServers() { return serverUris.keySet(); }
    int openConnectionCount() { return connected.size(); }

    @Override
    public List<LlmCompletionPort.ToolDefinition> definitions() {
        return role.allowedTools().stream().sorted().map(DEFINITIONS::get).toList();
    }

    @Override
    public String call(String toolName, Map<String, Object> arguments) {
        role.requireTool(toolName);
        if (!enabled) throw new IllegalStateException("MCP is disabled for this agent runtime");
        String server = serverFor(toolName);
        Session session = connected.computeIfAbsent(server,
                name -> sessions.connect(name, serverUris.get(name), timeout));
        if (!session.tools().contains(toolName)) {
            throw new SecurityException("MCP server did not negotiate the granted tool " + toolName);
        }
        Map<String, Object> bound = new LinkedHashMap<>(arguments == null ? Map.of() : arguments);
        Object claimedActor = bound.get("actor");
        if (claimedActor != null && !role.identity().role().equals(claimedActor)) {
            throw new SecurityException("MCP actor cannot differ from the active agent role");
        }
        bound.put("actor", role.identity().role());
        A2aW3cTraceContext trace = A2aW3cTraceContext.current();
        if (trace != null) bound = new LinkedHashMap<>(trace.addTo(bound));
        return session.call(toolName, Map.copyOf(bound));
    }

    private String serverFor(String tool) {
        if (tool.startsWith("context.")) return "repository-context-mcp";
        if (tool.startsWith("evidence.")) return "evidence-mcp";
        throw new SecurityException("Tool namespace is not available to agents");
    }

    @Override public void close() { connected.values().forEach(Session::close); connected.clear(); }

    interface SessionFactory { Session connect(String serverName, URI uri, Duration timeout); }
    interface Session extends AutoCloseable {
        Set<String> tools();
        String call(String toolName, Map<String, Object> arguments);
        @Override void close();
    }

    private static Map<String, LlmCompletionPort.ToolDefinition> definitionsByName() {
        Map<String, LlmCompletionPort.ToolDefinition> values = new LinkedHashMap<>();
        add(values, "context.list_tree", "List the bounded repository tree", Map.of(
                "path", string(), "depth", integer(1, 12), "max_entries", integer(1, 200)), List.of());
        add(values, "context.search_code", "Search code in the bounded repository", Map.of(
                "query", string(), "path", string(), "max_results", integer(1, 50)), List.of("query"));
        add(values, "context.read_file", "Read one bounded repository file", Map.of(
                "path", string(), "start_line", integer(1, 1_000_000),
                "end_line", integer(1, 1_000_000), "max_bytes", integer(1, 16_000)), List.of("path"));
        add(values, "context.get_repository_rules", "Read repository guidance", Map.of(), List.of());
        add(values, "context.get_dependencies", "Read direct dependencies", Map.of(
                "module", string(), "ecosystem", Map.of("type", "string",
                        "enum", List.of("MAVEN", "GRADLE", "NPM", "UNKNOWN")),
                "max_dependencies", integer(1, 100)), List.of("module"));
        add(values, "context.get_symbols", "Read a bounded symbol index", Map.of(
                "path", string(), "query", Map.of("type", "string", "maxLength", 256),
                "language", Map.of("type", "string", "maxLength", 32),
                "max_results", integer(1, 500)), List.of());
        add(values, "evidence.get_summary", "Read task-bound evidence metadata",
                Map.of("uri", string()), List.of("uri"));
        add(values, "evidence.read", "Read task-bound immutable evidence",
                Map.of("uri", string()), List.of("uri"));
        return Map.copyOf(values);
    }
    private static void add(Map<String, LlmCompletionPort.ToolDefinition> values, String name,
                            String description, Map<String, Object> properties, List<String> required) {
        values.put(name, new LlmCompletionPort.ToolDefinition(name, description, Map.of(
                "type", "object", "additionalProperties", false, "properties", properties,
                "required", required)));
    }
    private static Map<String, Object> string() {
        return Map.of("type", "string", "maxLength", 1024);
    }
    private static Map<String, Object> integer(int minimum, int maximum) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum);
    }
}
