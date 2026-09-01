package com.example.aifactory.config;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpNegativeReferenceCatalogTest {
    @Test
    void declaresTheSixMcp014FailClosedScenarios() throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(catalogFile()));

        assertEquals("1", root.path("schema_version").asText());
        assertTrue(root.path("delivery_mutation_forbidden").asBoolean());
        JsonNode cases = root.path("cases");
        assertTrue(cases.isArray());
        assertEquals(6, cases.size());

        Set<String> identifiers = new HashSet<>();
        for (JsonNode referenceCase : cases) {
            assertTrue(identifiers.add(referenceCase.path("id").asText()));
            assertFalse(referenceCase.path("trigger").asText().isBlank());
            assertFalse(referenceCase.path("expected_workflow_status").asText().isBlank());
            assertFalse(referenceCase.path("oracle").asText().isBlank());
            assertTrue(referenceCase.path("forbidden_effects").isArray());
            assertFalse(referenceCase.path("enforcement_status").asText().isBlank());
        }

        assertEquals(Set.of("MCP014-N01", "MCP014-N02", "MCP014-N03", "MCP014-N04", "MCP014-N05", "MCP014-N06"),
                identifiers);
    }

    private static Path catalogFile() {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : List.of(
                workingDirectory.resolve("resources/mcp/baselines/negative-cases-v1.json"),
                workingDirectory.resolve("../../resources/mcp/baselines/negative-cases-v1.json").normalize())) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate the MCP-014 negative-case catalog from " + workingDirectory);
    }
}
