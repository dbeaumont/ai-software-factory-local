package com.example.aifactory.a2a;

import com.example.aifactory.service.AgentCatalog;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Startup gate proving that every generated-card input still has one coherent authority. */
@Component
public final class A2aCatalogCoherenceGate {
    private final ObjectMapper mapper;
    private final AgentCatalog catalog;

    public A2aCatalogCoherenceGate(ObjectMapper mapper, AgentCatalog catalog) {
        this.mapper = mapper;
        this.catalog = catalog;
    }

    @PostConstruct
    public void validate() {
        Set<String> roles = catalog.roles().values().stream()
                .filter(role -> "agent".equals(role.kind()) || "sub-agent".equals(role.kind()))
                .map(AgentCatalog.Role::name).collect(Collectors.toSet());
        SecureUriPolicy.Resolver syntaxOnlyResolver = host ->
                List.of(java.net.InetAddress.getByName("192.0.2.10"));
        AllowListedAgentRegistry compose = new AllowListedAgentRegistry(
                mapper, catalog, "compose", syntaxOnlyResolver);
        AllowListedAgentRegistry gke = new AllowListedAgentRegistry(mapper, catalog, "gke", syntaxOnlyResolver);
        requireExact("Compose registry roles", roles, compose.entries().keySet());
        requireExact("GKE registry roles", roles, gke.entries().keySet());

        JsonNode mappings = json("a2a/skill-contract-map-v1.json");
        Map<String, Set<String>> inputs = contractsByRole(mappings.path("inputs"), "input_contract");
        Map<String, Set<String>> outputs = contractsByRole(mappings.path("outputs"), "output_contract");
        requireExact("A2A input roles", roles, inputs.keySet());
        requireExact("A2A output roles", roles, outputs.keySet());

        for (String roleName : roles) {
            AgentCatalog.Role role = catalog.require(roleName);
            Map<String, Object> manifest = yaml("agents/" + roleName + ".yaml");
            requireEqual("role", roleName, text(manifest, "role"));
            requireEqual("owner", role.owner(), text(manifest, "owner"));
            requireEqual("parent", role.parent(), nullable(manifest.get("parent")));
            requireExact(roleName + " permissions", Set.copyOf(role.tools()), strings(manifest, "allowed_tools"));
            requireExact(roleName + " delegation", Set.copyOf(role.mayDelegateTo()),
                    strings(manifest, "may_delegate_to"));
            requireExact(roleName + " input contracts", strings(manifest, "input_contracts"), inputs.get(roleName));
            requireExact(roleName + " output contracts", strings(manifest, "output_contracts"), outputs.get(roleName));
            requireEqual(roleName + " primary output", role.outputContract(),
                    outputs.get(roleName).contains(role.outputContract()) ? role.outputContract() : null);
            requireAddress(roleName, compose.require(roleName), false);
            requireAddress(roleName, gke.require(roleName), true);
        }
    }

    private void requireAddress(String role, AllowListedAgentRegistry.Entry entry, boolean gke) {
        String expectedHost = "a2a-" + role + (gke ? ".ai-factory-agents.svc.cluster.local" : "");
        requireEqual(role + " registry host", expectedHost, entry.endpoint().getHost());
        requireEqual(role + " registry endpoint path", "/a2a", entry.endpoint().getPath());
        requireEqual(role + " card path", "/.well-known/agent-card.json", entry.cardUri().getPath());
    }

    private static Map<String, Set<String>> contractsByRole(JsonNode mappings, String contractField) {
        Map<String, Set<String>> result = new HashMap<>();
        for (JsonNode mapping : mappings) {
            String role = mapping.path("role").asText();
            String contract = mapping.path(contractField).asText();
            String skill = mapping.path("skill_id").asText(null);
            if (role.isBlank() || contract.isBlank() || (skill != null && !skill.startsWith(role + "."))) {
                throw new CoherenceViolation("Invalid A2A contract mapping for " + role);
            }
            result.computeIfAbsent(role, ignored -> new HashSet<>()).add(contract);
        }
        return result;
    }

    private JsonNode json(String resource) {
        try (InputStream input = resource(resource)) {
            return mapper.readTree(input);
        } catch (Exception exception) {
            throw new CoherenceViolation("Cannot read " + resource, exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(String resource) {
        try (InputStream input = resource(resource)) {
            Object value = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
            if (!(value instanceof Map<?, ?>)) throw new CoherenceViolation(resource + " must be an object");
            return (Map<String, Object>) value;
        } catch (CoherenceViolation exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CoherenceViolation("Cannot read " + resource, exception);
        }
    }

    private static InputStream resource(String name) {
        InputStream input = A2aCatalogCoherenceGate.class.getClassLoader().getResourceAsStream(name);
        if (input == null) throw new CoherenceViolation("Missing " + name);
        return input;
    }

    private static Set<String> strings(Map<String, Object> source, String field) {
        if (!(source.get(field) instanceof List<?> list)) throw new CoherenceViolation(field + " must be a list");
        return list.stream().map(Object::toString).collect(Collectors.toSet());
    }

    private static String text(Map<String, Object> source, String field) {
        String value = nullable(source.get(field));
        if (value == null || value.isBlank()) throw new CoherenceViolation(field + " must not be blank");
        return value;
    }

    private static String nullable(Object value) { return value == null ? null : value.toString(); }

    static void requireExact(String label, Set<String> expected, Set<String> actual) {
        if (!expected.equals(actual)) {
            throw new CoherenceViolation(label + " diverge: expected=" + expected + ", actual=" + actual);
        }
    }

    private static void requireEqual(String label, Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new CoherenceViolation(label + " diverges: expected=" + expected + ", actual=" + actual);
        }
    }

    public static final class CoherenceViolation extends RuntimeException {
        public CoherenceViolation(String message) { super(message); }
        public CoherenceViolation(String message, Throwable cause) { super(message, cause); }
    }
}
