package com.example.aifactory.agentcore;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, framework-neutral authority for roles and capabilities. */
public final class AgentCatalog {
    private final String catalogId;
    private final Map<String, Role> roles;

    public AgentCatalog() {
        Map<String, Object> root = load("agents/catalog-v1.yaml");
        if (!"1".equals(root.get("version"))) throw new IllegalStateException("Unsupported agent catalog version");
        catalogId = required(root, "catalogId");
        roles = Map.copyOf(readRoles(map(root.get("roles"), "roles")));
        validate();
    }

    public String catalogId() { return catalogId; }
    public Map<String, Role> roles() { return roles; }
    public List<Role> agentRoles() {
        return roles.values().stream().filter(Role::isAgent).toList();
    }
    public Role require(String name) {
        Role role = roles.get(name);
        if (role == null) throw new IllegalArgumentException("Unknown agent role " + name);
        return role;
    }

    private static Map<String, Role> readRoles(Map<String, Object> source) {
        Map<String, Role> result = new LinkedHashMap<>();
        source.forEach((name, value) -> {
            Map<String, Object> role = map(value, "role " + name);
            result.put(name, new Role(name, required(role, "kind"), nullable(role.get("parent")),
                    required(role, "owner"), required(role, "autonomy"), Boolean.TRUE.equals(role.get("effectful")),
                    strings(role.get("mayDelegateTo")), strings(role.get("tools")),
                    required(role, "outputContract"), required(role, "humanGate")));
        });
        return result;
    }

    private void validate() {
        roles.values().forEach(role -> {
            if (role.parent() != null && !roles.containsKey(role.parent())) {
                throw new IllegalStateException("Unknown parent " + role.parent() + " for " + role.name());
            }
            role.mayDelegateTo().forEach(child -> {
                if (!roles.containsKey(child)) throw new IllegalStateException("Unknown delegated role " + child);
            });
            if (role.isAgent() && (role.effectful() || role.tools().stream().anyMatch(AgentCatalog::effectful))) {
                throw new IllegalStateException("Agent role exposes an effectful capability: " + role.name());
            }
        });
        if (agentRoles().size() != 14) throw new IllegalStateException("Catalog must expose exactly 14 agent roles");
    }

    private static boolean effectful(String tool) {
        return tool.startsWith("sandbox.") || tool.startsWith("assurance.") || tool.startsWith("scm.")
                || tool.equals("evidence.store") || tool.equals("evidence.create_manifest");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String resource) {
        try (InputStream input = AgentCatalog.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing " + resource);
            return map(new Yaml(new SafeConstructor(new LoaderOptions())).load(input), "catalog");
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
        public boolean isAgent() { return "agent".equals(kind) || "sub-agent".equals(kind); }
    }
}
