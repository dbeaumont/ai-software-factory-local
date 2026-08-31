package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSloPolicyTest {
    @Test
    @SuppressWarnings("unchecked")
    void definesMeasurableInitialObjectivesAndFailClosedInvariants() throws Exception {
        Map<String, Object> root;
        try (InputStream input = Files.newInputStream(policyFile())) {
            root = new Yaml().load(input);
        }

        assertEquals("1", root.get("version"));
        assertEquals("P28D", root.get("evaluationWindow"));
        Map<String, Map<String, Object>> objectives =
                (Map<String, Map<String, Object>>) root.get("objectives");
        assertEquals(0.995, objectives.get("availability").get("targetRatio"));
        assertEquals(0.95, objectives.get("readLatency").get("percentile"));
        assertEquals("PT5S", objectives.get("sandboxJobStartDelay").get("target"));
        assertEquals(0.005, objectives.get("mcpSystemErrorRate").get("targetMaxRatio"));

        Map<String, Integer> invariants = (Map<String, Integer>) root.get("invariants");
        assertEquals(0, invariants.get("falseSuccessCount"));
        assertEquals(0, invariants.get("scmMutationWithoutApprovalCount"));

        Map<String, Object> measurement = (Map<String, Object>) root.get("measurement");
        assertEquals(Boolean.TRUE, measurement.get("contentLabelsForbidden"));
        List<String> dimensions = (List<String>) measurement.get("requiredDimensions");
        assertTrue(dimensions.containsAll(List.of("server", "tool", "outcome", "error_code", "version")));
        assertFalse(((List<String>) measurement.get("requiredMetrics")).isEmpty());
    }

    private static Path policyFile() {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : List.of(
                workingDirectory.resolve("resources/mcp/policies/slo-policy-v1.yaml"),
                workingDirectory.resolve("../../resources/mcp/policies/slo-policy-v1.yaml").normalize())) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate the MCP SLO policy from " + workingDirectory);
    }
}
