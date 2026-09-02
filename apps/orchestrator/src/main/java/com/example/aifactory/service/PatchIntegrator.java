package com.example.aifactory.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Deterministic boundary between agent patch proposals and sandbox mutation. */
@Component
public final class PatchIntegrator {
    private final SandboxExecutor sandbox;

    public PatchIntegrator(SandboxExecutor sandbox) {
        this.sandbox = sandbox;
    }

    public IntegratedPatch validate(Path workspace, String taskId, String sourceCommit, String proposal) throws Exception {
        String patch = normalize(proposal);
        Path artifact = workspace.resolve("changes.patch");
        Files.writeString(artifact, patch, StandardCharsets.UTF_8);
        sandbox.checkPatch(workspace, taskId, sourceCommit);
        return new IntegratedPatch(patch, digestFor(patch));
    }

    public String apply(Path workspace, String taskId, String sourceCommit, IntegratedPatch integrated) throws Exception {
        Path artifact = workspace.resolve("changes.patch");
        String persisted = Files.readString(artifact, StandardCharsets.UTF_8);
        if (!digestFor(persisted).equals(integrated.digest())) {
            throw new IllegalStateException("Patch artifact changed after deterministic validation");
        }
        return sandbox.applyPatch(workspace, taskId, sourceCommit);
    }

    static String normalize(String proposal) {
        return UnifiedDiffNormalizer.normalize(stripFence(proposal));
    }

    static String stripFence(String value) {
        String out = value.strip();
        int fenceStart = out.indexOf("```");
        if (fenceStart >= 0) {
            int firstNewline = out.indexOf('\n', fenceStart);
            if (firstNewline >= 0) out = out.substring(firstNewline + 1);
            if (out.endsWith("```")) out = out.substring(0, out.length() - 3);
        }
        int diffStart = out.indexOf("diff --git ");
        if (diffStart > 0) out = out.substring(diffStart);
        return out.strip();
    }

    static String digestFor(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot digest integrated patch", exception);
        }
    }

    public record IntegratedPatch(String content, String digest) {
        public IntegratedPatch {
            if (content == null || content.isBlank() || digest == null || digest.isBlank()) {
                throw new IllegalArgumentException("Integrated patch content and digest are required");
            }
        }
    }
}
