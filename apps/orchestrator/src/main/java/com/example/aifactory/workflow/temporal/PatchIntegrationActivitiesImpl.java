package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.PatchIntegrationPlanner;
import com.example.aifactory.service.PatchIntegrator;
import com.example.aifactory.workflow.EvidenceRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Applies a workflow-authorized integration through the fixed sandbox profiles. */
@Component
public final class PatchIntegrationActivitiesImpl implements PatchIntegrationActivities {
    private final EvidenceRepository evidence;
    private final PatchIntegrator patchIntegrator;

    public PatchIntegrationActivitiesImpl(EvidenceRepository evidence, PatchIntegrator patchIntegrator) {
        this.evidence = evidence;
        this.patchIntegrator = patchIntegrator;
    }

    @Override
    public Result apply(Request request) {
        requireValid(request);
        DurableExecutionActivities.Metadata metadata = request.metadata();
        Path workspace = Path.of(request.workspace()).toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("Patch integration workspace does not exist");
        }
        StringBuilder consolidated = new StringBuilder();
        for (PatchArtifact patch : request.patches()) {
            EvidenceRepository.RawEvidence raw = evidence.read(new EvidenceRepository.ReadRequest(
                    metadata.taskId(), metadata.attemptId(), patch.uri(), "workflow",
                    "apply-patch-integration:" + request.planDigest()));
            if (!"code-patch".equals(raw.type()) || !patch.digest().equals(raw.digest())) {
                throw new SecurityException("Code patch evidence does not match the integration plan");
            }
            String content = strictUtf8(raw.content());
            if (!patch.digest().equals(PatchIntegrator.digestFor(content))
                    || !content.equals(PatchIntegrator.normalize(content))) {
                throw new SecurityException("Code patch evidence is not canonical or digest-bound");
            }
            consolidated.append(content);
        }
        try {
            PatchIntegrator.IntegratedPatch integrated = patchIntegrator.validate(workspace, metadata.taskId(),
                    metadata.sourceCommit(), consolidated.toString());
            String sandboxOutput = patchIntegrator.apply(
                    workspace, metadata.taskId(), metadata.sourceCommit(), integrated);
            return new Result(request.planDigest(), integrated.digest(), request.validationProfile(),
                    request.applicationProfile(), PatchIntegrator.digestFor(sandboxOutput), "APPLIED");
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Patch integration sandbox execution failed", exception);
        }
    }

    private static void requireValid(Request request) {
        if (request == null || request.metadata() == null || request.workspace() == null
                || request.workspace().isBlank() || request.workspace().indexOf('\0') >= 0
                || !PatchIntegrationWorkflow.PATCH_CHECK_PROFILE.equals(request.validationProfile())
                || !PatchIntegrationWorkflow.PATCH_APPLY_PROFILE.equals(request.applicationProfile())
                || request.patches().isEmpty() || request.patches().size() > 4) {
            throw new IllegalArgumentException("Patch integration activity request is invalid");
        }
        List<PatchIntegrationPlanner.PatchIdentity> identities = request.patches().stream().map(patch ->
                new PatchIntegrationPlanner.PatchIdentity(patch.nodeId(), patch.proposalId(), patch.digest())).toList();
        if (!PatchIntegrationPlanner.digestIdentities(identities).equals(request.planDigest())) {
            throw new SecurityException("Patch integration order differs from the workflow plan");
        }
    }

    private static String strictUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException exception) {
            throw new SecurityException("Code patch evidence is not valid UTF-8", exception);
        }
    }
}
