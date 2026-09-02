package com.example.aifactory.service;

import com.example.aifactory.workflow.EvidenceRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Workflow-owned conversion of a Developer patch result into immutable Evidence metadata. */
@Component
public final class CodePatchArtifactPublisher {
    private final CodeWorkspaceManager worktrees;
    private final EvidenceRepository evidence;

    public CodePatchArtifactPublisher(CodeWorkspaceManager worktrees, EvidenceRepository evidence) {
        this.worktrees = worktrees;
        this.evidence = evidence;
    }

    public PatchArtifact publish(CodeWorkspaceManager.Allocation allocation,
                                 PatchProposalValidator.ValidatedPatch patch) throws Exception {
        CodeWorkspaceManager.Allocation verified = worktrees.verify(allocation);
        byte[] content = patch.content().getBytes(StandardCharsets.UTF_8);
        if (content.length != patch.sizeBytes() || !PatchIntegrator.digestFor(patch.content()).equals(patch.digest())) {
            throw new SecurityException("Validated Code patch changed before evidence publication");
        }
        EvidenceRepository.StoredEvidence stored = evidence.store(new EvidenceRepository.StoreRequest(
                verified.taskId(), verified.attemptId(), "code-patch", "text/x-diff",
                content, patch.digest(), "workflow"));
        if (!patch.digest().equals(stored.digest()) || !"COMPLETE".equals(stored.status())
                || !"text/x-diff".equals(stored.mediaType()) || stored.sizeBytes() != content.length) {
            throw new SecurityException("Stored Code patch evidence does not match the submitted artifact");
        }
        return new PatchArtifact(verified.worktreeId(), verified.nodeId(), verified.sourceCommit(),
                stored.uri(), patch.digest(), stored.mediaType(), stored.sizeBytes());
    }

    public record PatchArtifact(String worktreeId, String nodeId, String sourceCommit, String uri,
                                String digest, String mediaType, long sizeBytes) {}
}
