package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryCorrelationVerifierTest {
    private static final String OUTPUT = "b".repeat(64);
    private static final String MANIFEST_ID = "c".repeat(64);
    private static final String MANIFEST_DIGEST = "d".repeat(64);
    private final DeliveryCorrelationVerifier verifier = new DeliveryCorrelationVerifier();

    @Test
    void correlatesSandboxEvidenceAssuranceManifestAndScm() {
        DeliveryCorrelationVerifier.VerifiedCorrelation result = verifier.verify(valid());

        assertThat(result.correlationId()).matches("[0-9a-f]{64}");
        assertThat(result.sandboxJobCount()).isEqualTo(1);
        assertThat(result.assuranceVerdict()).isEqualTo("ALLOW");
        assertThat(result.manifestId()).isEqualTo(MANIFEST_ID);
        assertThat(result.pullRequestId()).isEqualTo(42);
    }

    @Test
    void rejectsAnyBrokenDigestOrManifestBinding() {
        var valid = valid();
        assertThatThrownBy(() -> verifier.verify(new DeliveryCorrelationVerifier.DeliveryCorrelation(
                valid.traceId(), valid.taskId(), valid.attemptId(), valid.sourceCommit(), valid.sandboxJobs(),
                Map.of("tests", "e".repeat(64)), valid.assurance(), valid.manifest(), valid.scmDelivery())))
                .isInstanceOf(SecurityException.class).hasMessageContaining("sandbox job");
        assertThatThrownBy(() -> verifier.verify(new DeliveryCorrelationVerifier.DeliveryCorrelation(
                valid.traceId(), valid.taskId(), valid.attemptId(), valid.sourceCommit(), valid.sandboxJobs(),
                valid.evidenceDigests(), valid.assurance(), valid.manifest(),
                new DeliveryCorrelationVerifier.ScmDelivery(valid.sourceCommit(), "f".repeat(64),
                        MANIFEST_DIGEST, 42, "https://scm.example/pulls/42"))))
                .isInstanceOf(SecurityException.class).hasMessageContaining("SCM delivery");
    }

    private static DeliveryCorrelationVerifier.DeliveryCorrelation valid() {
        Map<String, String> evidence = Map.of("tests", OUTPUT);
        return new DeliveryCorrelationVerifier.DeliveryCorrelation(
                "1".repeat(32), "task-1", "attempt-1", "a".repeat(40),
                List.of(new DeliveryCorrelationVerifier.SandboxJob(
                        "2".repeat(32), "run-tests", "PASSED", OUTPUT)), evidence,
                new DeliveryCorrelationVerifier.AssuranceDecision("ALLOW", evidence),
                new DeliveryCorrelationVerifier.Manifest(MANIFEST_ID, MANIFEST_DIGEST, "COMPLETE"),
                new DeliveryCorrelationVerifier.ScmDelivery("a".repeat(40), MANIFEST_ID,
                        MANIFEST_DIGEST, 42, "https://scm.example/pulls/42"));
    }
}
