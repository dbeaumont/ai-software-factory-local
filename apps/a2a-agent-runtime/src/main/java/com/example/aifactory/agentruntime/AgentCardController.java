package com.example.aifactory.agentruntime;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Publishes the public card of the only role admitted in this runtime process. */
@RestController
final class AgentCardController {
    static final String WELL_KNOWN_PATH = "/.well-known/agent-card.json";

    private final AgentRuntimeProperties properties;
    private final AgentCardCatalogGenerator generator;

    AgentCardController(AgentRuntimeProperties properties, AgentCardCatalogGenerator generator) {
        this.properties = properties;
        this.generator = generator;
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
        card.put("url", properties.endpoint().toString());
        card.put("version", source.catalogId());
        card.put("capabilities", Map.of("streaming", false, "pushNotifications", false));
        card.put("defaultInputModes", source.skills().stream()
                .flatMap(skill -> skill.acceptedMediaTypes().stream()).distinct().sorted().toList());
        card.put("defaultOutputModes", List.of("application/json", "application/vnd.ai-factory.evidence-reference+json"));
        card.put("skills", source.skills().stream().map(skill -> Map.of(
                "id", skill.id(),
                "name", skill.inputContract(),
                "description", "Accepts " + skill.inputContract() + " schema " + skill.schemaVersion(),
                "tags", List.of(source.role(), source.owner()),
                "inputModes", skill.acceptedMediaTypes(),
                "outputModes", List.of("application/json", "application/vnd.ai-factory.evidence-reference+json"),
                "metadata", Map.of(
                        "inputContract", skill.inputContract(),
                        "inputSchema", skill.schemaUri(),
                        "outputContract", source.outputContract())))
                .toList());
        return Map.copyOf(card);
    }
}
