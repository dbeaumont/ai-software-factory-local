package com.example.aifactory.a2a;

import com.example.aifactory.service.AgentCatalog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Closed discovery registry; it never accepts an origin learned from an Agent Card or redirect. */
public final class AllowListedAgentRegistry {
    private static final String RESOURCE = "a2a/agent-registry-v1.json";

    private final Map<String, Entry> entries;
    private final SecureUriPolicy urlPolicy;

    public AllowListedAgentRegistry(ObjectMapper mapper, AgentCatalog catalog, String profile) {
        this(mapper, catalog, profile,
                host -> java.util.Arrays.asList(java.net.InetAddress.getAllByName(host)));
    }

    AllowListedAgentRegistry(ObjectMapper mapper, AgentCatalog catalog, String profile,
                             SecureUriPolicy.Resolver resolver) {
        this.entries = load(mapper, catalog, profile);
        Set<URI> allowed = entries.values().stream().flatMap(entry ->
                java.util.stream.Stream.of(entry.cardUri(), entry.endpoint())).collect(Collectors.toSet());
        this.urlPolicy = new SecureUriPolicy(allowed, resolver);
    }

    public Entry require(String role) {
        Entry entry = entries.get(role);
        if (entry == null) {
            throw new RegistryViolation("Role is not allow-listed: " + role);
        }
        urlPolicy.requireAllowed(entry.cardUri());
        urlPolicy.requireAllowed(entry.endpoint());
        return entry;
    }

    public Map<String, Entry> entries() {
        return entries;
    }

    public URI validateCardResponse(String role, URI finalUri, int redirects) {
        Entry expected = require(role);
        if (redirects != 0) {
            throw new RegistryViolation("Agent Card redirects are forbidden");
        }
        if (!expected.cardUri().equals(finalUri)) {
            throw new RegistryViolation("Agent Card was served from an unexpected origin");
        }
        return finalUri;
    }

    private static Map<String, Entry> load(ObjectMapper mapper, AgentCatalog catalog, String profile) {
        try (InputStream input = AllowListedAgentRegistry.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (input == null) throw new RegistryViolation("Missing " + RESOURCE);
            JsonNode root = mapper.readTree(input);
            if (!"1".equals(root.path("version").asText())) {
                throw new RegistryViolation("Unsupported agent registry version");
            }
            JsonNode configured = root.path("profiles").path(profile);
            if (!configured.isObject()) throw new RegistryViolation("Unknown registry profile: " + profile);
            Map<String, Entry> result = new LinkedHashMap<>();
            configured.properties().forEach(entry -> {
                URI origin = URI.create(entry.getValue().asText());
                validateOrigin(origin);
                result.put(entry.getKey(), new Entry(
                        origin.resolve("/.well-known/agent-card.json"), origin.resolve("/a2a")));
            });
            Set<String> expected = catalog.roles().values().stream()
                    .filter(role -> "agent".equals(role.kind()) || "sub-agent".equals(role.kind()))
                    .map(AgentCatalog.Role::name).collect(Collectors.toSet());
            if (!result.keySet().equals(expected)) {
                throw new RegistryViolation("Agent registry and role catalog diverge");
            }
            return Map.copyOf(result);
        } catch (RegistryViolation exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RegistryViolation("Cannot load agent registry", exception);
        }
    }

    private static void validateOrigin(URI origin) {
        if (!"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null
                || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || (origin.getPath() != null && !origin.getPath().isEmpty())) {
            throw new RegistryViolation("Registry entry must be an HTTPS origin");
        }
        SecureUriPolicy.validateNetworkTargetSyntax(origin);
    }

    public record Entry(URI cardUri, URI endpoint) {}

    public static final class RegistryViolation extends RuntimeException {
        public RegistryViolation(String message) { super(message); }
        public RegistryViolation(String message, Throwable cause) { super(message, cause); }
    }
}
