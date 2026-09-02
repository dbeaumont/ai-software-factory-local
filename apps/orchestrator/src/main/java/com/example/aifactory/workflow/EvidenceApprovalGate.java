package com.example.aifactory.workflow;

import com.example.aifactory.workflow.temporal.SoftwareFactoryWorkflow;
import org.springframework.stereotype.Service;

/** Creates and validates the immutable evidence manifest before opening human approval. */
@Service
public final class EvidenceApprovalGate {
    private final EvidenceRepository evidence;

    public EvidenceApprovalGate(EvidenceRepository evidence) {
        this.evidence = evidence;
    }

    public SoftwareFactoryWorkflow.ApprovalRequest prepare(EvidenceRepository.ManifestRequest request) {
        EvidenceRepository.StoredManifest manifest = evidence.createManifest(request);
        if (!"COMPLETE".equals(manifest.status())) {
            throw new IllegalStateException("human approval requires a complete evidence manifest");
        }
        return new SoftwareFactoryWorkflow.ApprovalRequest(
                manifest.manifestId(), manifest.uri(), manifest.digest());
    }
}
