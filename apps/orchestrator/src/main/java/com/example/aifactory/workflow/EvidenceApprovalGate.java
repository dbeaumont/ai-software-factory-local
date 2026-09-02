package com.example.aifactory.workflow;

import com.example.aifactory.workflow.temporal.SoftwareFactoryWorkflow;
import org.springframework.stereotype.Service;

/** Creates and validates the immutable evidence manifest before opening human approval. */
@Service
public final class EvidenceApprovalGate {
    private static final java.util.Set<String> REQUIRED = java.util.Set.of(
            "plan", "patch", "metadata", "tests", "sonar", "sbom", "trivy", "review", "approval");
    private final EvidenceRepository evidence;

    public EvidenceApprovalGate(EvidenceRepository evidence) {
        this.evidence = evidence;
    }

    public SoftwareFactoryWorkflow.ApprovalRequest prepare(EvidenceRepository.ManifestRequest request) {
        if (request == null || !REQUIRED.equals(request.artifacts().keySet())) {
            throw new IllegalStateException("consolidation requires the exact mandatory evidence set");
        }
        request.artifacts().forEach((type, reference) -> {
            String expectedPrefix = "evidence://" + request.taskId() + '/' + request.attemptId() + '/'
                    + type + '/';
            if (!"COMPLETE".equals(reference.status()) || !reference.uri().startsWith(expectedPrefix)
                    || !reference.digest().matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("evidence reference is partial or outside task scope: " + type);
            }
            EvidenceRepository.EvidenceSummary actual = evidence.getSummary(
                    request.taskId(), request.attemptId(), reference.uri(), "workflow");
            if (!reference.uri().equals(actual.uri()) || !reference.digest().equals(actual.digest())
                    || !type.equals(actual.type()) || !"COMPLETE".equals(actual.status())) {
                throw new IllegalStateException("evidence reference is missing or altered: " + type);
            }
        });
        EvidenceRepository.StoredManifest manifest = evidence.createManifest(request);
        if (!"COMPLETE".equals(manifest.status())) {
            throw new IllegalStateException("human approval requires a complete evidence manifest");
        }
        return new SoftwareFactoryWorkflow.ApprovalRequest(
                manifest.manifestId(), manifest.uri(), manifest.digest());
    }
}
