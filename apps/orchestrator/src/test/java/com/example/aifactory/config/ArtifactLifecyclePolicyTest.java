package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactLifecyclePolicyTest {
    @Test
    @SuppressWarnings("unchecked")
    void everyEvidenceTypeHasClassificationRetentionEncryptionAndFailClosedPurge() throws Exception {
        Path policyFile = Path.of(System.getProperty("user.dir"))
                .resolve("../../resources/multiagents/policies/artifact-lifecycle-policy-v1.yaml").normalize();
        Map<String, Object> policy = new Yaml().load(Files.readString(policyFile));
        Map<String, Object> storage = (Map<String, Object>) policy.get("storage");
        Map<String, Object> encryption = (Map<String, Object>) storage.get("encryption");
        Map<String, Map<String, Object>> types = (Map<String, Map<String, Object>>) policy.get("artifact_types");
        Map<String, Object> purge = (Map<String, Object>) policy.get("purge");

        assertThat(encryption).containsEntry("required", true)
                .containsEntry("algorithm", "AES-256-GCM")
                .containsKey("key_source_target");
        assertThat(types.keySet()).containsExactlyInAnyOrderElementsOf(List.of(
                "plan", "evaluation", "patch", "integration", "metadata", "tests", "sonar", "sbom", "trivy", "review",
                "approval", "manifest"));
        types.forEach((name, rule) -> {
            assertThat(rule.get("classification")).as(name).isIn("INTERNAL", "CONFIDENTIAL");
            assertThat((Integer) rule.get("retention_days")).as(name).isPositive();
        });
        assertThat(purge).containsEntry("failure_mode", "fail-closed-and-alert")
                .containsEntry("projection_grace_days", 30);
        assertThat((List<String>) purge.get("authority_order"))
                .containsSubsequence("verify_no_legal_hold", "purge_encrypted_content_in_evidence_mcp",
                        "write_digest_only_tombstone", "purge_projection_metadata_after_grace_period");
    }
}
