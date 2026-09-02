package com.example.aifactory.workflow;

import com.example.aifactory.config.McpClientProperties;
import com.example.aifactory.service.McpToolInvoker;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceApprovalGateTest {
    @Test
    void createsTheFinalManifestBeforeReturningAnApprovalRequest() {
        String manifestId = "a".repeat(64);
        String manifestDigest = "b".repeat(64);
        McpToolInvoker mcp = new McpToolInvoker() {
            @Override
            public tools.jackson.databind.JsonNode call(String serverName, String toolName,
                                                         Map<String, Object> arguments) {
                if ("evidence.get_summary".equals(toolName)) {
                    String uri = arguments.get("uri").toString();
                    String[] parts = uri.split("/");
                    return new ObjectMapper().valueToTree(Map.of(
                            "uri", uri, "type", parts[parts.length - 2], "digest", parts[parts.length - 1],
                            "status", "COMPLETE", "classification", "INTERNAL", "size_bytes", 5));
                }
                assertThat(toolName).isEqualTo("evidence.create_manifest");
                assertThat(arguments).containsEntry("actor", "workflow")
                        .containsEntry("patch_digest", "c".repeat(64));
                return new ObjectMapper().valueToTree(Map.ofEntries(
                        Map.entry("manifest_id", manifestId),
                        Map.entry("uri", "evidence://task-1/attempt-1/manifest/" + manifestId),
                        Map.entry("digest", manifestDigest), Map.entry("status", "COMPLETE"),
                        Map.entry("classification", "CONFIDENTIAL"),
                        Map.entry("retain_until", "2027-09-02T00:00:00Z"),
                        Map.entry("created_at", "2026-09-02T00:00:00Z")));
            }

            @Override public Availability availability(String serverName) { return new Availability(true, null); }
        };
        EvidenceApprovalGate gate = new EvidenceApprovalGate(new McpEvidenceRepository(mcp, properties()));

        var approval = gate.prepare(manifestRequest());

        assertThat(approval.manifestId()).isEqualTo(manifestId);
        assertThat(approval.digest()).isEqualTo(manifestDigest);
        assertThat(approval.uri()).isEqualTo("evidence://task-1/attempt-1/manifest/" + manifestId);
    }

    private static EvidenceRepository.ManifestRequest manifestRequest() {
        Map<String, EvidenceRepository.EvidenceReference> artifacts = new LinkedHashMap<>();
        for (String type : List.of("plan", "patch", "metadata", "tests", "sonar", "sbom", "trivy",
                "review", "approval")) {
            String digest = (type.equals("patch") ? "c" : "d").repeat(64);
            artifacts.put(type, new EvidenceRepository.EvidenceReference(
                    "evidence://task-1/attempt-1/" + type + '/' + digest, digest, "COMPLETE"));
        }
        var decision = new EvidenceRepository.PolicyDecision("1", "task-1", "attempt-1", "delivery.gate",
                "1.0.0", "ALLOW", List.of("passed"), Map.of("tests", "d".repeat(64)),
                Instant.parse("2026-09-02T00:00:00Z"));
        return new EvidenceRepository.ManifestRequest("task-1", "attempt-1", "customer-api", "e".repeat(40),
                "c".repeat(64), artifacts, decision, "workflow");
    }

    private static McpClientProperties properties() {
        McpClientProperties.RetryPolicy retry = new McpClientProperties.RetryPolicy(
                2, Duration.ofMillis(100), Duration.ofSeconds(1), 2, 0);
        McpClientProperties.Server evidence = new McpClientProperties.Server(true,
                java.net.URI.create("http://evidence-mcp:8095"), "evidence-mcp", Duration.ofSeconds(20),
                "evidence-mcp", "0.1.0", Set.of("evidence.store", "evidence.create_manifest"));
        return new McpClientProperties(true, Duration.ofSeconds(20), 65_536, 16, 4,
                Set.of("2025-11-25"), new McpClientProperties.Retry(retry, retry), Map.of("evidence", evidence));
    }
}
