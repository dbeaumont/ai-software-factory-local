package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Host-owned immutable catalog of agent identities and capabilities. */
@Component
public final class AgentCatalog {
    private final String catalogId;
    private final Map<String, Role> roles;

    public AgentCatalog() {
        Map<String, Object> root = load();
        if (!"1".equals(root.get("version"))) throw new IllegalStateException("Unsupported agent catalog version");
        this.catalogId = required(root, "catalogId");
        this.roles = Map.copyOf(readRoles(map(root.get("roles"), "roles")));
        validateReferences();
    }

    public String catalogId() { return catalogId; }
    public Map<String, Role> roles() { return roles; }

    public Role require(String name) {
        Role role = roles.get(name);
        if (role == null) throw new IllegalArgumentException("Unknown agent role " + name);
        return role;
    }

    private Map<String, Role> readRoles(Map<String, Object> source) {
        Map<String, Role> result = new LinkedHashMap<>();
        source.forEach((name, value) -> {
            Map<String, Object> role = map(value, "role " + name);
            result.put(name, new Role(name, required(role, "kind"), nullable(role.get("parent")),
                    required(role, "owner"), required(role, "autonomy"), Boolean.TRUE.equals(role.get("effectful")),
                    strings(role.get("mayDelegateTo")), strings(role.get("tools")), required(role, "outputContract"),
                    required(role, "humanGate")));
        });
        return result;
    }

    private void validateReferences() {
        roles.values().forEach(role -> {
            if (role.parent() != null && !roles.containsKey(role.parent()))
                throw new IllegalStateException("Unknown parent " + role.parent() + " for " + role.name());
            role.mayDelegateTo().forEach(child -> {
                if (!roles.containsKey(child)) throw new IllegalStateException("Unknown delegated role " + child);
            });
        });
        Role supervisor = roles.get("supervisor");
        if (supervisor == null || supervisor.effectful()
                || supervisor.tools().stream().anyMatch(AgentCatalog::effectfulTool)) {
            throw new IllegalStateException("Supervisor must exist and expose read-only tools only");
        }
        supervisor.mayDelegateTo().forEach(child -> {
            if (!"supervisor".equals(roles.get(child).parent())) {
                throw new IllegalStateException("Supervisor delegation target has another parent: " + child);
            }
        });
    }

    private static boolean effectfulTool(String name) {
        return name.startsWith("sandbox.") || name.startsWith("assurance.") || name.startsWith("scm.")
                || name.equals("evidence.store") || name.equals("evidence.create_manifest");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        try (InputStream input = AgentCatalog.class.getClassLoader().getResourceAsStream("agents/catalog-v1.yaml")) {
            if (input == null) throw new IllegalStateException("Missing agents/catalog-v1.yaml");
            Object value = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            return map(value, "catalog");
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load agent catalog", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String field) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalStateException(field + " must be an object");
        return (Map<String, Object>) value;
    }

    private static String required(Map<String, Object> source, String field) {
        String value = nullable(source.get(field));
        if (value == null || value.isBlank()) throw new IllegalStateException(field + " is required");
        return value;
    }

    private static String nullable(Object value) { return value == null ? null : value.toString(); }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalStateException("Expected a list");
        return list.stream().map(Object::toString).toList();
    }

    public record Role(String name, String kind, String parent, String owner, String autonomy, boolean effectful,
                       List<String> mayDelegateTo, List<String> tools, String outputContract, String humanGate) {
        public Role {
            mayDelegateTo = List.copyOf(mayDelegateTo);
            tools = List.copyOf(tools);
        }
    }
}
