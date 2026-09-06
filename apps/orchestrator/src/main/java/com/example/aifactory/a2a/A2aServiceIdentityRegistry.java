package com.example.aifactory.a2a;

import com.example.aifactory.service.AgentCatalog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Host-owned role-to-workload identity bindings. Credentials are referenced, never embedded. */
public final class A2aServiceIdentityRegistry {
    private static final String RESOURCE = "a2a/service-identities-v1.json";

    private final String audience;
    private final Identity orchestrator;
    private final Map<String, Identity> agents;

    public A2aServiceIdentityRegistry(ObjectMapper mapper, AgentCatalog catalog) {
        this(mapper, catalog, resource());
    }

    A2aServiceIdentityRegistry(ObjectMapper mapper, AgentCatalog catalog, InputStream input) {
        try (input) {
            JsonNode root = mapper.readTree(input);
            if (!"1".equals(root.path("version").asText())) {
                throw new IdentityViolation("Unsupported service identity registry version");
            }
            this.audience = required(root, "audience");
            this.orchestrator = identity(root.path("orchestrator"), "orchestrator");
            this.agents = Map.copyOf(readAgents(root.path("agents")));
            validateCatalog(catalog);
            validateUniqueness();
        } catch (IdentityViolation exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IdentityViolation("Cannot load service identity registry", exception);
        }
    }

    public String audience() {
        return audience;
    }

    public Identity orchestrator() {
        return orchestrator;
    }

    public Map<String, Identity> agents() {
        return agents;
    }

    public Identity requireAgent(String role) {
        Identity identity = agents.get(role);
        if (identity == null) throw new IdentityViolation("Unknown A2A service role: " + role);
        return identity;
    }

    /** Binds both OAuth client identity and mTLS subject to one and only one role. */
    public Identity authorizeAgent(String role, String clientId, URI subject) {
        Identity expected = requireAgent(role);
        if (!expected.clientId().equals(clientId) || !expected.subject().equals(subject)) {
            throw new IdentityViolation("A2A service identity is not authorized for role " + role);
        }
        return expected;
    }

    private Map<String, Identity> readAgents(JsonNode node) {
        if (!node.isObject()) throw new IdentityViolation("agents must be an object");
        Map<String, Identity> result = new LinkedHashMap<>();
        node.properties().forEach(entry -> result.put(entry.getKey(), identity(entry.getValue(), entry.getKey())));
        return result;
    }

    private void validateCatalog(AgentCatalog catalog) {
        Set<String> expected = catalog.roles().values().stream()
                .filter(role -> "agent".equals(role.kind()) || "sub-agent".equals(role.kind()))
                .map(AgentCatalog.Role::name)
                .collect(Collectors.toSet());
        if (!agents.keySet().equals(expected)) {
            throw new IdentityViolation("Service identities and role catalog diverge");
        }
    }

    private void validateUniqueness() {
        List<Identity> identities = java.util.stream.Stream.concat(
                java.util.stream.Stream.of(orchestrator), agents.values().stream()).toList();
        requireUnique(identities.stream().map(Identity::clientId).toList(), "OAuth client IDs");
        requireUnique(identities.stream().map(Identity::subject).toList(), "mTLS subjects");
        requireUnique(identities.stream().map(Identity::credentialRef).toList(), "credential references");
    }

    private static void requireUnique(List<?> values, String field) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IdentityViolation(field + " must not be shared between A2A roles");
        }
    }

    private static Identity identity(JsonNode node, String name) {
        if (!node.isObject()) throw new IdentityViolation(name + " identity must be an object");
        URI subject = URI.create(required(node, "subject"));
        URI credentialRef = URI.create(required(node, "credentialRef"));
        if (!"spiffe".equals(subject.getScheme()) || subject.getHost() == null
                || subject.getQuery() != null || subject.getFragment() != null) {
            throw new IdentityViolation(name + " subject must be a SPIFFE ID");
        }
        if (!"secret".equals(credentialRef.getScheme()) || credentialRef.getHost() == null
                || credentialRef.getQuery() != null || credentialRef.getFragment() != null) {
            throw new IdentityViolation(name + " credentialRef must be an external secret reference");
        }
        return new Identity(required(node, "clientId"), subject, credentialRef);
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value == null || value.isBlank()) throw new IdentityViolation(field + " is required");
        return value;
    }

    private static InputStream resource() {
        InputStream input = A2aServiceIdentityRegistry.class.getClassLoader().getResourceAsStream(RESOURCE);
        if (input == null) throw new IdentityViolation("Missing " + RESOURCE);
        return input;
    }

    public record Identity(String clientId, URI subject, URI credentialRef) {}

    public static final class IdentityViolation extends RuntimeException {
        public IdentityViolation(String message) { super(message); }
        public IdentityViolation(String message, Throwable cause) { super(message, cause); }
    }
}
