package com.example.aifactory.workflow;

import com.example.aifactory.config.McpClientProperties;
import com.example.aifactory.service.McpToolInvoker;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpEvidenceRepositoryTest {
    @Test
    void storesAllWorkflowArtifactFamiliesThroughEvidenceMcp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        McpToolInvoker mcp = new McpToolInvoker() {
            @Override
            public tools.jackson.databind.JsonNode call(String server, String tool, Map<String, Object> arguments) {
                assertThat(server).isEqualTo("evidence-mcp");
                assertThat(tool).isEqualTo("evidence.store");
                assertThat(arguments).containsEntry("schema_version", "1").containsEntry("actor", "workflow");
                String uri = "evidence://" + arguments.get("task_id") + '/' + arguments.get("attempt_id") + '/'
                        + arguments.get("type") + '/' + arguments.get("digest");
                return mapper.valueToTree(Map.ofEntries(Map.entry("uri", uri),
                        Map.entry("digest", arguments.get("digest")), Map.entry("status", "COMPLETE"),
                        Map.entry("media_type", arguments.get("media_type")), Map.entry("size_bytes", 5),
                        Map.entry("classification", "INTERNAL"),
                        Map.entry("retain_until", "2027-09-02T00:00:00Z"),
                        Map.entry("stored_at", "2026-09-02T00:00:00Z")));
            }

            @Override public Availability availability(String serverName) { return new Availability(true, null); }
        };
        McpEvidenceRepository repository = new McpEvidenceRepository(mcp, properties());

        for (String type : Set.of("plan", "evaluation", "patch", "integration", "review")) {
            byte[] content = "proof".getBytes(StandardCharsets.UTF_8);
            String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
            EvidenceRepository.StoredEvidence stored = repository.store(new EvidenceRepository.StoreRequest(
                    "task-1", "attempt-1", type, "text/plain", content, digest, "workflow"));
            assertThat(stored.uri()).isEqualTo("evidence://task-1/attempt-1/" + type + '/' + digest);
        }
    }

    @Test
    void rawReadIsLimitedToAuditedActorsAndReverifiedLocally() throws Exception {
        byte[] content = "proof".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
        String uri = "evidence://task-1/attempt-1/review/" + digest;
        McpToolInvoker mcp = new McpToolInvoker() {
            @Override
            public tools.jackson.databind.JsonNode call(String server, String tool, Map<String, Object> arguments) {
                assertThat(tool).isEqualTo("evidence.read");
                assertThat(arguments).containsEntry("actor", "independent-reviewer")
                        .containsEntry("purpose", "human-review");
                return new ObjectMapper().valueToTree(Map.of(
                        "uri", uri, "type", "review", "digest", digest, "status", "COMPLETE",
                        "classification", "CONFIDENTIAL",
                        "content_base64", java.util.Base64.getEncoder().encodeToString(content)));
            }

            @Override public Availability availability(String serverName) { return new Availability(true, null); }
        };
        McpEvidenceRepository repository = new McpEvidenceRepository(mcp, properties());

        assertThat(repository.read(new EvidenceRepository.ReadRequest(
                "task-1", "attempt-1", uri, "independent-reviewer", "human-review")).content()).isEqualTo(content);
        assertThatThrownBy(() -> repository.read(new EvidenceRepository.ReadRequest(
                "task-1", "attempt-1", uri, "developer", "human-review"))).isInstanceOf(SecurityException.class);
    }

    @Test
    void agentSummaryContainsOnlyBoundedMetadata() {
        String digest = "a".repeat(64);
        String uri = "evidence://task-1/attempt-1/tests/" + digest;
        McpToolInvoker mcp = new McpToolInvoker() {
            @Override
            public tools.jackson.databind.JsonNode call(String server, String tool, Map<String, Object> arguments) {
                assertThat(tool).isEqualTo("evidence.get_summary");
                assertThat(arguments).containsEntry("actor", "security-agent");
                return new ObjectMapper().valueToTree(Map.of(
                        "uri", uri, "type", "tests", "digest", digest, "status", "COMPLETE",
                        "classification", "INTERNAL", "size_bytes", 123));
            }

            @Override public Availability availability(String serverName) { return new Availability(true, null); }
        };

        EvidenceRepository.EvidenceSummary summary = new McpEvidenceRepository(mcp, properties())
                .getSummary("task-1", "attempt-1", uri, "security-agent");

        assertThat(summary.sizeBytes()).isEqualTo(123);
        assertThat(summary.digest()).isEqualTo(digest);
    }

    private static McpClientProperties properties() {
        McpClientProperties.RetryPolicy retry = new McpClientProperties.RetryPolicy(
                2, Duration.ofMillis(100), Duration.ofSeconds(1), 2, 0);
        McpClientProperties.Server evidence = new McpClientProperties.Server(true,
                java.net.URI.create("http://evidence-mcp:8095"), "evidence-mcp", Duration.ofSeconds(20),
                "evidence-mcp", "0.1.0",
                Set.of("evidence.store", "evidence.create_manifest", "evidence.get_summary", "evidence.read"));
        return new McpClientProperties(true, Duration.ofSeconds(20), 65_536, 16, 4,
                Set.of("2025-11-25"), new McpClientProperties.Retry(retry, retry), Map.of("evidence", evidence));
    }
}
