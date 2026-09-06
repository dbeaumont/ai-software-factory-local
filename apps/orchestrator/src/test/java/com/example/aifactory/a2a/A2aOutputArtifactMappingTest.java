package com.example.aifactory.a2a;

import com.example.aifactory.service.AgentCatalog;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class A2aOutputArtifactMappingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsAllFifteenDeclaredOutputsAndExactlyOnePrimaryPerAgentRole() throws Exception {
        JsonNode mappings = json("a2a/skill-contract-map-v1.json").path("outputs");
        AgentCatalog catalog = new AgentCatalog();
        Set<String> actual = new HashSet<>();
        Map<String, Long> primaryCount = new java.util.HashMap<>();
        mappings.forEach(mapping -> {
            String role = mapping.path("role").asText();
            String contract = mapping.path("output_contract").asText();
            assertThat(actual.add(role + "|" + contract)).isTrue();
            String schema = mapping.path("schema_uri").asText().replace("classpath:/", "");
            assertThat(getClass().getClassLoader().getResource(schema)).as(schema).isNotNull();
            assertThat(mapping.path("media_type").asText()).isEqualTo(A2aMediaTypes.JSON);
            assertThat(mapping.path("max_evidence_references").asInt()).isEqualTo(32);
            if (mapping.path("primary_artifact").asBoolean()) {
                primaryCount.merge(role, 1L, Long::sum);
                assertThat(contract).isEqualTo(catalog.require(role).outputContract());
            }
        });
        Set<String> expected = manifestOutputs(catalog.roles().keySet());
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected).hasSize(15);
        catalog.roles().values().stream().filter(role -> !"control-plane".equals(role.kind())).forEach(role ->
                assertThat(primaryCount.getOrDefault(role.name(), 0L)).as(role.name()).isEqualTo(1));
    }

    @Test
    void resultSchemaAllowsOnePrimaryAndOnlyBoundedEvidenceReferences() throws Exception {
        Schema schema = resultSchema();
        JsonNode fixture = json("a2a/fixtures/a2a-result-v1.json");
        assertThat(schema.validate(fixture)).isEmpty();
        ObjectNode missingPrimary = (ObjectNode) fixture.deepCopy();
        missingPrimary.remove("primary_artifact");
        assertThat(schema.validate(missingPrimary)).isNotEmpty();
        ObjectNode tooMany = (ObjectNode) fixture.deepCopy();
        var references = (tools.jackson.databind.node.ArrayNode) tooMany.path("evidence_references");
        JsonNode reference = references.get(0);
        for (int index = 1; index < 33; index++) references.add(reference.deepCopy());
        assertThat(schema.validate(tooMany)).isNotEmpty();
    }

    @SuppressWarnings("unchecked")
    private Set<String> manifestOutputs(Set<String> catalogRoles) throws Exception {
        Set<String> result = new HashSet<>();
        for (String role : catalogRoles) {
            if ("workflow".equals(role)) continue;
            try (InputStream input = getClass().getClassLoader().getResourceAsStream("agents/" + role + ".yaml")) {
                Map<String, Object> manifest = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
                ((java.util.List<Object>) manifest.get("output_contracts"))
                        .forEach(contract -> result.add(role + "|" + contract));
            }
        }
        return Set.copyOf(result);
    }

    private Schema resultSchema() throws Exception {
        String reference;
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("a2a/schemas/a2a-artifact-reference-v1.schema.json")) {
            reference = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("a2a/schemas/a2a-result-v1.schema.json")) {
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                    builder -> builder.schemas(Map.of(
                            "https://ai-factory.local/a2a/schemas/a2a-artifact-reference-v1.schema.json", reference)))
                    .getSchema(input);
        }
    }

    private JsonNode json(String resource) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing " + resource);
            return mapper.readTree(input);
        }
    }
}
