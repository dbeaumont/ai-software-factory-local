package com.example.aifactory.config;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class McpContextShadowCampaignTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void campaignMatchesItsSchemaAndCoversTheRequiredSample() throws Exception {
        Path root = repositoryRoot();
        Path schemaPath = root.resolve("resources/mcp/schemas/context-shadow-campaign-v1.schema.json");
        Path campaignPath = root.resolve("resources/mcp/baselines/context-shadow-campaign-v1.json");
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        Schema schema;
        try (var input = Files.newInputStream(schemaPath)) {
            schema = registry.getSchema(input);
        }
        JsonNode campaign = mapper.readTree(campaignPath.toFile());

        assertThat(schema.validate(campaign)).isEmpty();
        assertThat(campaign.path("tasks")).hasSize(20);
        Set<String> ids = new HashSet<>();
        Set<String> ecosystems = new HashSet<>();
        Set<String> repositories = new HashSet<>();
        Set<String> categories = new HashSet<>();
        campaign.path("tasks").forEach(task -> {
            ids.add(task.path("id").asText());
            ecosystems.add(task.path("ecosystem").asText());
            repositories.add(task.path("repository").asText());
            categories.add(task.path("category").asText());
        });
        assertThat(ids).hasSize(20);
        assertThat(ecosystems).containsExactlyInAnyOrder("MAVEN", "GRADLE", "NPM");
        assertThat(repositories).containsExactlyInAnyOrder("customer-api", "inventory-gradle", "checkout-node");
        assertThat(categories).contains("SIMPLE", "VALIDATION", "MULTI_FILE", "RULES", "NEGATIVE");
    }

    private static Path repositoryRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        for (Path candidate : new Path[]{workingDirectory, workingDirectory.resolve("../..").normalize()}) {
            if (Files.isRegularFile(candidate.resolve("resources/mcp/baselines/context-shadow-campaign-v1.json"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate repository root");
    }
}
