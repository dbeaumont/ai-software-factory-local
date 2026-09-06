package com.example.aifactory.agentruntime;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;

/** Publishes the public card of the only role admitted in this runtime process. */
@RestController
final class AgentCardController {
    static final String WELL_KNOWN_PATH = "/.well-known/agent-card.json";

    private final AgentRuntimeProperties properties;
    private final A2aSecurityProperties security;
    private final A2aCardIdentityProperties identity;
    private final AgentCardCatalogGenerator generator;
    private final A2aAgentCardSigner signer;

    AgentCardController(AgentRuntimeProperties properties, A2aSecurityProperties security,
                        A2aCardIdentityProperties identity,
                        AgentCardCatalogGenerator generator, A2aAgentCardSigner signer) {
        this.properties = properties;
        this.security = security;
        this.identity = identity;
        this.generator = generator;
        this.signer = signer;
    }

    @GetMapping(path = WELL_KNOWN_PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> publicCard() {
        AgentCardCatalogGenerator.GeneratedAgentCard source = generator.generate().get(properties.role());
        if (source == null) {
            throw new IllegalStateException("No generated card for active role " + properties.role());
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("protocolVersion", "1.0");
        card.put("name", "AI Factory " + source.role());
        card.put("description", "Role " + source.role() + " owned by " + source.owner());
        card.put("provider", Map.of(
                "organization", identity.providerName(),
                "url", identity.providerUrl().toString()));
        card.put("url", properties.endpoint().toString());
        card.put("preferredTransport", "JSONRPC");
        card.put("additionalInterfaces", List.of(Map.of(
                "url", properties.endpoint().toString(),
                "transport", "JSONRPC")));
        card.put("version", source.catalogId());
        card.put("capabilities", Map.of("streaming", false, "pushNotifications", false));
        if (security.enabled()) {
            card.put("securitySchemes", Map.of(
                    "mutualTLS", Map.of("type", "mutualTLS", "description", "Workload mTLS certificate"),
                    "oauth2", Map.of(
                            "type", "oauth2",
                            "flows", Map.of("clientCredentials", Map.of(
                                    "tokenUrl", security.oauth2TokenUrl().toString(),
                                    "scopes", operationScopes())))));
            card.put("security", List.of(Map.of(
                    "mutualTLS", List.of(),
                    "oauth2", List.of("a2a.invoke", "a2a.role." + source.role()))));
        }
        card.put("defaultInputModes", source.skills().stream()
                .flatMap(skill -> skill.acceptedMediaTypes().stream()).distinct().sorted().toList());
        card.put("defaultOutputModes", List.of("application/json", "application/vnd.ai-factory.evidence-reference+json"));
        card.put("metadata", Map.of(
                "issuer", identity.issuer(),
                "role", source.role(),
                "expiresAt", Instant.now().plus(identity.validity()).toString()));
        card.put("skills", source.skills().stream().map(skill -> Map.of(
                "id", skill.id(),
                "name", skill.inputContract(),
                "description", "Accepts " + skill.inputContract() + " schema " + skill.schemaVersion(),
                "tags", List.of(source.role(), source.owner()),
                "inputModes", skill.acceptedMediaTypes(),
                "outputModes", List.of("application/json", "application/vnd.ai-factory.evidence-reference+json"),
                "security", security.enabled() ? List.of(Map.of(
                        "mutualTLS", List.of(),
                        "oauth2", List.of("a2a.invoke", "a2a.skill." + skill.id()))) : List.of(),
                "metadata", Map.of(
                        "inputContract", skill.inputContract(),
                        "inputSchema", skill.schemaUri(),
                        "outputContract", source.outputContract())))
                .toList());
        card.put("signatures", signer.sign(card));
        return Map.copyOf(card);
    }

    private Map<String, String> operationScopes() {
        return Map.of(
                "a2a.invoke", "Submit an A2A task",
                "a2a.read", "Read an A2A task",
                "a2a.cancel", "Cancel an A2A task",
                "a2a.role." + properties.role(), "Invoke the active role");
    }
}
