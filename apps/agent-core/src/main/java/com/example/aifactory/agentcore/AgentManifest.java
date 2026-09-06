package com.example.aifactory.agentcore;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A single role manifest validated against the central catalog and embedded resources. */
public record AgentManifest(String manifestId, String role, String version, String owner, String parent,
                            String kind, String autonomy, String promptName, List<String> inputContracts,
                            List<String> outputContracts, Set<String> allowedTools,
                            Set<String> mayDelegateTo, String humanGate, String compatibilityPromptName) {
    public AgentManifest {
        inputContracts = List.copyOf(inputContracts);
        outputContracts = List.copyOf(outputContracts);
        allowedTools = Set.copyOf(allowedTools);
        mayDelegateTo = Set.copyOf(mayDelegateTo);
    }

    public static AgentManifest load(String requestedRole, AgentCatalog catalog, PromptRepository prompts) {
        if (requestedRole == null || !requestedRole.matches("[a-z][a-z0-9-]{1,63}")
                || "workflow".equals(requestedRole)) {
            throw new IllegalStateException("AI_FACTORY_AGENT_ROLE must select one non-control agent role");
        }
        AgentCatalog.Role catalogRole;
        try {
            catalogRole = catalog.require(requestedRole);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalStateException("AI_FACTORY_AGENT_ROLE is unknown: " + requestedRole, unknown);
        }
        if (!catalogRole.isAgent()) throw new IllegalStateException("Selected role is not an agent");
        Map<String, Object> descriptor = descriptor(requestedRole);
        if (!"1".equals(text(descriptor, "schema_version"))) {
            throw new IllegalStateException("Unsupported role manifest schema");
        }
        String promptPath = text(descriptor, "prompt");
        if (!promptPath.matches("prompts/[a-z][a-z0-9-]{1,63}\\.md")) {
            throw new IllegalStateException("Role prompt path is invalid");
        }
        String promptName = promptPath.substring("prompts/".length(), promptPath.length() - ".md".length());
        prompts.fingerprint(promptName);
        String compatibilityPromptName = promptName(descriptor.get("compatibility_prompt"));
        if (compatibilityPromptName != null) prompts.fingerprint(compatibilityPromptName);
        List<String> outputs = strings(descriptor, "output_contracts");
        Set<String> tools = Set.copyOf(strings(descriptor, "allowed_tools"));
        Set<String> delegates = Set.copyOf(strings(descriptor, "may_delegate_to"));
        requireEqual("role", requestedRole, text(descriptor, "role"));
        requireEqual("kind", catalogRole.kind(), text(descriptor, "kind"));
        requireEqual("owner", catalogRole.owner(), text(descriptor, "owner"));
        requireEqual("parent", catalogRole.parent(), nullable(descriptor.get("parent")));
        requireEqual("autonomy", catalogRole.autonomy(), text(descriptor, "autonomy"));
        requireEqual("human gate", catalogRole.humanGate(), text(descriptor, "human_gate"));
        if (Boolean.TRUE.equals(descriptor.get("effectful")) || catalogRole.effectful()) {
            throw new IllegalStateException("Agent runtime cannot load an effectful role");
        }
        if (!tools.equals(Set.copyOf(catalogRole.tools()))) {
            throw new IllegalStateException("Role tools diverge from central catalog");
        }
        if (!delegates.equals(Set.copyOf(catalogRole.mayDelegateTo()))) {
            throw new IllegalStateException("Role delegation targets diverge from central catalog");
        }
        if (!outputs.contains(catalogRole.outputContract())) {
            throw new IllegalStateException("Role output contracts diverge from central catalog");
        }
        return new AgentManifest(text(descriptor, "manifest_id"), requestedRole, text(descriptor, "version"),
                catalogRole.owner(), catalogRole.parent(), catalogRole.kind(), catalogRole.autonomy(), promptName,
                strings(descriptor, "input_contracts"), outputs, tools, delegates, catalogRole.humanGate(),
                compatibilityPromptName);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> descriptor(String role) {
        String resource = "agents/" + role + ".yaml";
        try (InputStream input = AgentManifest.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing embedded role manifest " + resource);
            Object value = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            if (!(value instanceof Map<?, ?>)) throw new IllegalStateException("Role manifest must be an object");
            return (Map<String, Object>) value;
        } catch (Exception exception) {
            throw exception instanceof IllegalStateException state ? state
                    : new IllegalStateException("Cannot load role manifest", exception);
        }
    }
    private static List<String> strings(Map<String, Object> source, String field) {
        if (!(source.get(field) instanceof List<?> values)) {
            throw new IllegalStateException(field + " must be a list");
        }
        return values.stream().map(Object::toString).toList();
    }
    private static String text(Map<String, Object> source, String field) {
        String value = nullable(source.get(field));
        if (value == null || value.isBlank()) throw new IllegalStateException(field + " is required");
        return value;
    }
    private static String nullable(Object value) { return value == null ? null : value.toString(); }
    private static String promptName(Object value) {
        String path = nullable(value);
        if (path == null) return null;
        if (!path.matches("prompts/[a-z][a-z0-9-]{1,63}\\.md")) {
            throw new IllegalStateException("Compatibility prompt path is invalid");
        }
        return path.substring("prompts/".length(), path.length() - ".md".length());
    }
    private static void requireEqual(String field, Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new IllegalStateException("Role " + field + " diverges from central catalog");
        }
    }
}
