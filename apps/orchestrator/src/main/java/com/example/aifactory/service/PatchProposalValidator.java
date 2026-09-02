package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Binds patch proposal metadata to the actual normalized unified diff before evidence publication. */
@Component
public final class PatchProposalValidator {
    private static final Pattern DIFF_HEADER = Pattern.compile("diff --git a/([^\\s]+) b/([^\\s]+)");
    private final PatchScopeValidator scopes;

    public PatchProposalValidator(PatchScopeValidator scopes) {
        this.scopes = scopes;
    }

    public ValidatedPatch validate(JsonNode codeTask, JsonNode proposal, String rawPatch) {
        scopes.validateDeveloper(codeTask, proposal);
        String patch = PatchIntegrator.normalize(rawPatch);
        byte[] bytes = patch.getBytes(StandardCharsets.UTF_8);
        String digest = PatchIntegrator.digestFor(patch);
        long maximumBytes = codeTask.path("scope").path("max_patch_bytes").asLong(-1);
        if (bytes.length < 1 || maximumBytes < 1 || bytes.length > maximumBytes) {
            throw invalid("actual patch byte limit exceeded");
        }
        if (!digest.equals(proposal.path("patch_digest").asText())
                || !digest.equals(proposal.path("diff_artifact").path("digest").asText())) {
            throw invalid("actual patch digest differs from proposal metadata");
        }
        if (bytes.length != proposal.path("diff_artifact").path("size_bytes").asLong(-1)) {
            throw invalid("actual patch size differs from proposal metadata");
        }
        List<FileChange> actual = changes(patch);
        Set<FileChange> declared = new LinkedHashSet<>();
        for (JsonNode file : proposal.path("files_touched")) {
            declared.add(new FileChange(file.path("path").asText(),
                    file.has("previous_path") ? file.path("previous_path").asText() : null,
                    file.path("operation").asText()));
        }
        if (declared.size() != proposal.path("files_touched").size()
                || !declared.equals(new LinkedHashSet<>(actual))) {
            throw invalid("actual changed paths or operations differ from proposal metadata");
        }
        return new ValidatedPatch(patch, digest, bytes.length, actual);
    }

    private static List<FileChange> changes(String patch) {
        String[] lines = patch.split("\\n");
        List<FileChange> changes = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].startsWith("diff --git ")) continue;
            Matcher header = DIFF_HEADER.matcher(lines[index]);
            if (!header.matches()) throw invalid("diff header contains an unsupported or ambiguous path");
            int end = index + 1;
            while (end < lines.length && !lines[end].startsWith("diff --git ")) end++;
            String oldPath = header.group(1);
            String newPath = header.group(2);
            String previousPath = null;
            String path = newPath;
            String operation = "MODIFY";
            for (int line = index + 1; line < end; line++) {
                if (lines[line].startsWith("new file mode ") || "--- /dev/null".equals(lines[line])) {
                    operation = "ADD";
                } else if (lines[line].startsWith("deleted file mode ") || "+++ /dev/null".equals(lines[line])) {
                    operation = "DELETE";
                    path = oldPath;
                } else if (lines[line].startsWith("rename from ")) {
                    operation = "RENAME";
                    previousPath = lines[line].substring("rename from ".length());
                } else if (lines[line].startsWith("rename to ")) {
                    operation = "RENAME";
                    path = lines[line].substring("rename to ".length());
                }
            }
            if ("RENAME".equals(operation) && previousPath == null) previousPath = oldPath;
            changes.add(new FileChange(path, previousPath, operation));
            index = end - 1;
        }
        if (changes.isEmpty()) throw invalid("actual patch contains no file diff");
        return List.copyOf(changes);
    }

    private static SecurityException invalid(String reason) {
        return new SecurityException("Patch proposal rejected before publication: " + reason);
    }

    public record FileChange(String path, String previousPath, String operation) {}

    public record ValidatedPatch(String content, String digest, long sizeBytes, List<FileChange> changes) {
        public ValidatedPatch {
            changes = List.copyOf(changes);
        }
    }
}
