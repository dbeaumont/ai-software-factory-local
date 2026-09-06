package com.example.aifactory.a2a;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class A2aInputSkillMappingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsEveryManifestInputExactlyOnceToAStableSkillAndExistingSchema() throws Exception {
        JsonNode catalog = loadJson("a2a/skill-contract-map-v1.json");
        assertThat(catalog.path("catalog_version").asText()).isEqualTo("1");
        assertThat(catalog.path("protocol_version").asText()).isEqualTo("1.0");
        Set<String> expected = manifestInputs();
        Set<String> actual = new HashSet<>();
        Set<String> skills = new HashSet<>();

        catalog.path("inputs").forEach(mapping -> {
            String role = mapping.path("role").asText();
            String contract = mapping.path("input_contract").asText();
            assertThat(actual.add(role + "|" + contract)).as(role + " / " + contract).isTrue();
            assertThat(skills.add(mapping.path("skill_id").asText())).isTrue();
            assertThat(mapping.path("skill_id").asText()).isEqualTo(role + "." + contract);
            assertThat(mapping.path("schema_version").asText()).isEqualTo("1");
            String schema = mapping.path("schema_uri").asText().replace("classpath:/", "");
            assertThat(getClass().getClassLoader().getResource(schema)).as(schema).isNotNull();
            assertThat(mapping.path("accepted_media_types")).hasSize(2);
            mapping.path("accepted_media_types").forEach(media ->
                    assertThat(A2aMediaTypes.isSupported(media.asText())).as(media.asText()).isTrue());
        });
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(actual).hasSize(33);
    }

    @SuppressWarnings("unchecked")
    private Set<String> manifestInputs() throws Exception {
        Set<String> result = new HashSet<>();
        try (var files = Files.list(Path.of("../../resources/agents"))) {
            files.filter(path -> path.toString().endsWith(".yaml"))
                    .filter(path -> !path.getFileName().toString().equals("catalog-v1.yaml"))
                    .forEach(path -> {
                        try (InputStream input = Files.newInputStream(path)) {
                            Map<String, Object> manifest = new Yaml(new SafeConstructor(new LoaderOptions())).load(input);
                            String role = manifest.get("role").toString();
                            Object inputs = manifest.get("input_contracts");
                            if (inputs instanceof java.util.List<?> contracts) {
                                contracts.forEach(contract -> result.add(role + "|" + contract));
                            }
                        } catch (Exception exception) { throw new IllegalStateException(exception); }
                    });
        }
        return Set.copyOf(result);
    }

    private JsonNode loadJson(String name) throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("Missing " + name);
            return mapper.readTree(input);
        }
    }
}
