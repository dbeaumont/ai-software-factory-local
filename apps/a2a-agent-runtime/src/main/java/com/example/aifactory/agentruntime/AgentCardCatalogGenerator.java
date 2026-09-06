package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentCatalog;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds card source data exclusively from the authoritative role and skill catalogs. */
@Component
public final class AgentCardCatalogGenerator {
    private static final String SKILLS_RESOURCE = "a2a/skill-contract-map-v1.json";

    private final AgentCatalog catalog;
    private final ObjectMapper objectMapper;

    public AgentCardCatalogGenerator(AgentCatalog catalog, ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.objectMapper = objectMapper;
    }

    public Map<String, GeneratedAgentCard> generate() {
        Map<String, List<GeneratedSkill>> skillsByRole = readSkills();
        Map<String, GeneratedAgentCard> result = new LinkedHashMap<>();
        catalog.agentRoles().stream().sorted(Comparator.comparing(AgentCatalog.Role::name)).forEach(role -> {
            List<GeneratedSkill> skills = skillsByRole.getOrDefault(role.name(), List.of());
            if (skills.isEmpty()) {
                throw new IllegalStateException("No A2A skill mapped for role " + role.name());
            }
            result.put(role.name(), new GeneratedAgentCard(
                    catalog.catalogId(),
                    role.name(),
                    role.kind(),
                    role.owner(),
                    role.autonomy(),
                    role.parent(),
                    role.mayDelegateTo(),
                    role.tools(),
                    role.outputContract(),
                    skills));
        });
        if (!skillsByRole.keySet().equals(result.keySet())) {
            throw new IllegalStateException("Skill catalog and agent catalog roles diverge");
        }
        return Map.copyOf(result);
    }

    private Map<String, List<GeneratedSkill>> readSkills() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(SKILLS_RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("Missing " + SKILLS_RESOURCE);
            }
            JsonNode root = objectMapper.readTree(input);
            if (!"1".equals(root.path("catalog_version").asText())) {
                throw new IllegalStateException("Unsupported A2A skill catalog version");
            }
            Map<String, List<GeneratedSkill>> skillsByRole = new LinkedHashMap<>();
            for (JsonNode skill : root.path("inputs")) {
                List<String> mediaTypes = new ArrayList<>();
                skill.path("accepted_media_types").forEach(value -> mediaTypes.add(value.asText()));
                GeneratedSkill generated = new GeneratedSkill(
                        required(skill, "skill_id"),
                        required(skill, "input_contract"),
                        required(skill, "schema_version"),
                        required(skill, "schema_uri"),
                        mediaTypes);
                skillsByRole.computeIfAbsent(required(skill, "role"), ignored -> new ArrayList<>()).add(generated);
            }
            skillsByRole.values().forEach(skills -> skills.sort(Comparator.comparing(GeneratedSkill::id)));
            return skillsByRole;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load A2A skill catalog", exception);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalStateException("Missing skill field " + field);
        }
        return value;
    }

    public record GeneratedAgentCard(
            String catalogId,
            String role,
            String kind,
            String owner,
            String autonomy,
            String parent,
            List<String> mayDelegateTo,
            List<String> tools,
            String outputContract,
            List<GeneratedSkill> skills) {
        public GeneratedAgentCard {
            mayDelegateTo = List.copyOf(mayDelegateTo);
            tools = List.copyOf(tools);
            skills = List.copyOf(skills);
        }
    }

    public record GeneratedSkill(
            String id,
            String inputContract,
            String schemaVersion,
            String schemaUri,
            List<String> acceptedMediaTypes) {
        public GeneratedSkill {
            acceptedMediaTypes = List.copyOf(acceptedMediaTypes);
        }
    }
}
