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

class McpCompatibilityRollbackPolicyTest {
    @Test
    @SuppressWarnings("unchecked")
    void keepsNAndNMinusOneWithoutUnsafeRollbackEscapeHatches() throws Exception {
        Map<String, Object> root;
        try (InputStream input = Files.newInputStream(policyFile())) {
            root = new Yaml().load(input);
        }

        Map<String, Object> compatibility = (Map<String, Object>) root.get("compatibility");
        assertEquals("N_AND_N_MINUS_1", compatibility.get("strategy"));
        assertEquals("P28D", compatibility.get("minimumOverlap"));
        assertEquals(Boolean.TRUE, compatibility.get("breakingChangesRequireNewMajor"));

        Map<String, Object> rollback = (Map<String, Object>) root.get("rollback");
        assertEquals(Boolean.FALSE, rollback.get("directFallbackAllowed"));
        assertEquals(Boolean.FALSE, rollback.get("approvalBypassAllowed"));
        assertEquals(Boolean.FALSE, rollback.get("blindEffectfulRetryAllowed"));
        assertFalse(((List<String>) rollback.get("triggers")).isEmpty());

        Map<String, Object> artifacts = (Map<String, Object>) root.get("artifacts");
        assertEquals(Boolean.TRUE, artifacts.get("mutableTagsForbiddenForPromotion"));
        assertEquals(Boolean.TRUE, artifacts.get("destructiveDownMigrationForbidden"));

        Map<String, Object> state = (Map<String, Object>) root.get("state");
        Map<String, Object> snapshots = (Map<String, Object>) state.get("sandboxSnapshots");
        assertEquals("N_AND_N_MINUS_1", snapshots.get("readerMustSupport"));
        assertTrue(((List<String>) rollback.get("order")).contains("reconcile in-flight jobs by handle and idempotency key"));
    }

    private static Path policyFile() {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate : List.of(
                workingDirectory.resolve("resources/mcp/policies/compatibility-rollback-policy-v1.yaml"),
                workingDirectory.resolve("../../resources/mcp/policies/compatibility-rollback-policy-v1.yaml").normalize())) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Cannot locate the MCP compatibility policy from " + workingDirectory);
    }
}
