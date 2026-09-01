package com.example.aifactory.config;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssuranceSchemasTest {
    private static final String SHA = "a".repeat(40);
    private static final String DIGEST = "b".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalizedAssuranceGoldenDocumentsMatchAllV1Schemas() throws Exception {
        Map<String, Object> evidence = Map.of("uri", "evidence://task-1/attempt-1/tests", "digest", DIGEST,
                "status", "COMPLETE");
        Map<String, JsonNode> examples = new LinkedHashMap<>();
        examples.put("test-result-v1.schema.json", mapper.valueToTree(Map.ofEntries(
                Map.entry("schema_version", "1"), Map.entry("task_id", "task-1"), Map.entry("attempt_id", "attempt-1"),
                Map.entry("source_commit", SHA), Map.entry("verdict", "PASSED"), Map.entry("framework", "JUnit"),
                Map.entry("total", 3), Map.entry("passed", 3), Map.entry("failed", 0), Map.entry("skipped", 0),
                Map.entry("duration_ms", 1200), Map.entry("evidence", evidence))));
        examples.put("quality-gate-result-v1.schema.json", mapper.valueToTree(Map.ofEntries(
                Map.entry("schema_version", "1"), Map.entry("task_id", "task-1"), Map.entry("attempt_id", "attempt-1"),
                Map.entry("source_commit", SHA), Map.entry("provider", "SonarQube"), Map.entry("gate", "default"),
                Map.entry("verdict", "PASSED"), Map.entry("conditions", List.of(Map.of("metric", "bugs", "operator", "EQ", "threshold", 0, "actual", 0, "status", "PASSED"))),
                Map.entry("evidence", evidence))));
        examples.put("vulnerability-result-v1.schema.json", mapper.valueToTree(Map.ofEntries(
                Map.entry("schema_version", "1"), Map.entry("task_id", "task-1"), Map.entry("attempt_id", "attempt-1"),
                Map.entry("source_commit", SHA), Map.entry("scanner", "Trivy"), Map.entry("verdict", "PASSED"),
                Map.entry("findings", List.of()), Map.entry("summary", Map.of("unknown", 0, "low", 0, "medium", 0, "high", 0, "critical", 0)),
                Map.entry("evidence", evidence))));
        examples.put("sbom-reference-v1.schema.json", mapper.valueToTree(Map.ofEntries(
                Map.entry("schema_version", "1"), Map.entry("task_id", "task-1"), Map.entry("attempt_id", "attempt-1"),
                Map.entry("source_commit", SHA), Map.entry("format", "CYCLONEDX_JSON"), Map.entry("spec_version", "1.6"),
                Map.entry("component_count", 4), Map.entry("uri", "evidence://task-1/attempt-1/sbom"), Map.entry("digest", DIGEST), Map.entry("status", "COMPLETE"))));
        Map<String, Object> decision = Map.ofEntries(Map.entry("schema_version", "1"), Map.entry("task_id", "task-1"),
                Map.entry("attempt_id", "attempt-1"), Map.entry("policy_id", "delivery.gate"), Map.entry("policy_version", "1.0.0"),
                Map.entry("decision", "ALLOW"), Map.entry("reasons", List.of("all mandatory evidence passed")),
                Map.entry("input_digests", Map.of("tests", DIGEST)), Map.entry("decided_at", "2026-09-02T00:00:00Z"));
        examples.put("policy-decision-v1.schema.json", mapper.valueToTree(decision));
        examples.put("evidence-manifest-v1.schema.json", mapper.valueToTree(Map.ofEntries(
                Map.entry("schema_version", "1"), Map.entry("manifest_id", DIGEST), Map.entry("task_id", "task-1"),
                Map.entry("attempt_id", "attempt-1"), Map.entry("repository_id", "customer-api"), Map.entry("source_commit", SHA),
                Map.entry("patch_digest", DIGEST), Map.entry("artifacts", Map.ofEntries(Map.entry("plan", evidence), Map.entry("patch", evidence),
                        Map.entry("metadata", evidence), Map.entry("tests", evidence), Map.entry("sonar", evidence), Map.entry("sbom", evidence),
                        Map.entry("trivy", evidence), Map.entry("review", evidence), Map.entry("approval", evidence))),
                Map.entry("policy_decision", decision), Map.entry("created_at", "2026-09-02T00:00:00Z"))));

        for (Map.Entry<String, JsonNode> example : examples.entrySet()) {
            Schema schema = schema(example.getKey());
            assertTrue(schema.validate(example.getValue()).isEmpty(), example.getKey());
            ((tools.jackson.databind.node.ObjectNode) example.getValue()).put("schema_version", "2");
            assertFalse(schema.validate(example.getValue()).isEmpty(), example.getKey() + " must reject version 2");
        }
    }

    private Schema schema(String name) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path file = root.resolve("../../resources/mcp/schemas").normalize().resolve(name);
        assertTrue(Files.isRegularFile(file), file.toString());
        return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(Files.newInputStream(file));
    }
}
