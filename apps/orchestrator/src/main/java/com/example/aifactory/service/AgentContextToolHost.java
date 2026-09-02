package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import com.example.aifactory.workflow.EvidenceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public final class AgentContextToolHost {
    private static final Map<String, Set<String>> BUSINESS_ARGUMENTS = Map.of(
            "context.list_tree", Set.of("path", "depth", "max_entries", "include", "exclude", "cursor"),
            "context.read_file", Set.of("path", "start_line", "end_line", "max_bytes"),
            "context.search_code", Set.of("query", "path", "max_results"),
            "context.get_repository_rules", Set.of(),
            "context.get_dependencies", Set.of("module", "ecosystem", "max_dependencies", "cursor"),
            "context.get_symbols", Set.of("path", "query", "language", "max_results", "cursor"),
            "evidence.get_summary", Set.of("uri"),
            "evidence.read", Set.of("uri"));
    private static final ObjectMapper JSON = new ObjectMapper();

    private final McpToolInvoker invoker;
    private final McpFactoryProperties properties;
    private final OperationalKillSwitch killSwitch;
    private final EvidenceRepository evidence;

    @Autowired
    public AgentContextToolHost(McpToolInvoker invoker, McpFactoryProperties properties,
                                OperationalKillSwitch killSwitch, EvidenceRepository evidence) {
        this.invoker = invoker;
        this.properties = properties;
        this.killSwitch = killSwitch;
        this.evidence = evidence;
    }

    AgentContextToolHost(McpToolInvoker invoker, McpFactoryProperties properties,
                         OperationalKillSwitch killSwitch) {
        this(invoker, properties, killSwitch, null);
    }

    public List<LlmGatewayClient.ToolDefinition> definitions() {
        return List.of(
                definition("context.list_tree", "List the bounded repository tree.", Map.of(
                        "path", string(), "depth", integer(1, 12), "max_entries", integer(1, 200))),
                requiredDefinition("context.read_file", "Read a bounded repository text file.", "path", Map.of(
                        "path", string(), "start_line", integer(1, 1000000), "end_line", integer(1, 1000000),
                        "max_bytes", integer(1, 16000))),
                requiredDefinition("context.search_code", "Search a literal string in repository files.", "query", Map.of(
                        "query", string(), "path", string(), "max_results", integer(1, 50))),
                definition("context.get_repository_rules", "Read repository guidance files.", Map.of()),
                requiredDefinition("context.get_dependencies", "Read direct dependencies without executing code.",
                        "module", Map.of("module", string(), "ecosystem", Map.of("type", "string",
                                "enum", List.of("MAVEN", "GRADLE", "NPM", "UNKNOWN")),
                                "max_dependencies", integer(1, 100))),
                definition("context.get_symbols", "Read a bounded symbol index by path or query.", Map.of(
                        "path", string(), "query", Map.of("type", "string", "maxLength", 256),
                        "language", Map.of("type", "string", "maxLength", 32),
                        "max_results", integer(1, 500))),
                requiredDefinition("evidence.get_summary", "Read task-bound evidence metadata without content.",
                        "uri", Map.of("uri", string())),
                requiredDefinition("evidence.read", "Read task-bound raw evidence with an immutable audit event.",
                        "uri", Map.of("uri", string())));
    }

    public AgentToolLoop.ToolExecutor executor(String taskId, String attemptId, String sourceCommit, String role) {
        return call -> {
            Set<String> allowed = BUSINESS_ARGUMENTS.get(call.name());
            if (allowed == null || !allowed.containsAll(call.arguments().keySet())) {
                throw new AgentToolLoop.AgentLoopException("invalid_arguments",
                        "Model supplied unknown or host-controlled tool arguments");
            }
            if (call.name().startsWith("evidence.")) {
                if (evidence == null) throw new AgentToolLoop.AgentLoopException(
                        "evidence_unavailable", "Evidence repository is unavailable");
                String uri = requiredString(call.arguments(), "uri");
                Object result = "evidence.get_summary".equals(call.name())
                        ? evidence.getSummary(taskId, attemptId, uri, role)
                        : evidence.read(new EvidenceRepository.ReadRequest(
                        taskId, attemptId, uri, role, "human-review"));
                return JSON.valueToTree(result).toString();
            }
            Map<String, Object> arguments = new LinkedHashMap<>(McpRequestMetadata.create(
                    taskId, sourceCommit, role, Duration.ofSeconds(20)).arguments());
            arguments.put("attempt_id", attemptId);
            arguments.putAll(call.arguments());
            JsonNode result = invoker.call(properties.repositoryContextServerName(), call.name(), arguments);
            return result.toString();
        };
    }

    public AgentToolLoop.ToolAuthorization authorization() {
        return ToolPermissionMatrix.readOnlyAgents(killSwitch);
    }

    private static LlmGatewayClient.ToolDefinition definition(String name, String description,
                                                              Map<String, Object> properties) {
        return schema(name, description, properties, List.of());
    }

    private static LlmGatewayClient.ToolDefinition requiredDefinition(String name, String description,
                                                                      String required,
                                                                      Map<String, Object> properties) {
        return schema(name, description, properties, List.of(required));
    }

    private static LlmGatewayClient.ToolDefinition schema(String name, String description,
                                                          Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", required);
        return new LlmGatewayClient.ToolDefinition(name, description, Map.copyOf(schema));
    }

    private static Map<String, Object> string() { return Map.of("type", "string", "maxLength", 1024); }
    private static Map<String, Object> integer(int min, int max) {
        return Map.of("type", "integer", "minimum", min, "maximum", max);
    }

    private static String requiredString(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new AgentToolLoop.AgentLoopException("invalid_arguments", name + " is required");
        }
        return text;
    }
}
