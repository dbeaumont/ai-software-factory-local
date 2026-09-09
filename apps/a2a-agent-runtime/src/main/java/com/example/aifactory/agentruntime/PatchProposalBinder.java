package com.example.aifactory.agentruntime;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Derives security-sensitive patch metadata from the admitted task and the model-produced diff. */
final class PatchProposalBinder {
    private static final Pattern HUNK_HEADER =
            Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)");
    private static final Pattern DIFF_HEADER = Pattern.compile("diff --git a/([^\\s]+) b/([^\\s]+)");
    private static final Set<String> OUTPUT_FIELDS = Set.of(
            "schema_version", "proposal_id", "code_task_id", "task_id", "attempt_id", "node_id",
            "source_commit", "worktree_id", "scope_digest", "patch_digest", "patch", "files_touched",
            "diff_artifact", "summary", "created_at");

    private PatchProposalBinder() {
    }

    static String bind(ObjectMapper mapper, AgentExecutionWorker.Request request, String modelOutput) {
        if (!"patch-proposal-v1".equals(request.outputContract())) return modelOutput;
        try {
            JsonNode parsed;
            try {
                parsed = mapper.readTree(modelOutput);
            } catch (Exception invalidJson) {
                parsed = null;
            }
            if ((parsed == null || parsed.isTextual()) && modelOutput.contains("diff --git ")) {
                ObjectNode rawProposal = mapper.createObjectNode();
                rawProposal.put("proposal_id", "proposal-" + request.taskId());
                rawProposal.put("patch", parsed != null && parsed.isTextual() ? parsed.asText() : modelOutput);
                rawProposal.put("summary", "Patch proposed for " + request.input().path("code_task_id").asText());
                parsed = rawProposal;
            }
            if (!(parsed instanceof ObjectNode proposal) || !proposal.path("patch").isTextual()) {
                return modelOutput;
            }
            String modelPatch = proposal.path("patch").asText();
            if (!modelPatch.contains("diff --git ")) return modelOutput;
            String patch = normalize(modelPatch);
            String digest = digest(patch);
            byte[] bytes = patch.getBytes(StandardCharsets.UTF_8);
            JsonNode task = request.input();

            List<String> unexpected = new ArrayList<>();
            proposal.propertyNames().forEach(name -> {
                if (!OUTPUT_FIELDS.contains(name)) unexpected.add(name);
            });
            unexpected.forEach(proposal::remove);
            proposal.put("schema_version", "1");
            proposal.put("code_task_id", task.path("code_task_id").asText());
            proposal.put("task_id", request.taskId());
            proposal.put("attempt_id", request.attemptId());
            proposal.put("node_id", task.path("node_id").asText());
            proposal.put("source_commit", task.path("source_commit").asText());
            proposal.put("worktree_id", task.path("worktree_id").asText());
            proposal.put("scope_digest", task.path("scope_digest").asText());
            proposal.put("patch", patch);
            proposal.put("patch_digest", digest);
            proposal.put("created_at", task.path("issued_at").asText());

            ObjectNode artifact = proposal.putObject("diff_artifact");
            artifact.put("uri", "evidence://" + request.taskId() + '/' + request.attemptId()
                    + "/code-patch/" + digest);
            artifact.put("digest", digest);
            artifact.put("media_type", "text/x-diff");
            artifact.put("size_bytes", bytes.length);
            ArrayNode files = proposal.putArray("files_touched");
            changes(patch).forEach(change -> {
                ObjectNode file = files.addObject();
                file.put("path", change.path());
                file.put("operation", change.operation());
                if (change.previousPath() != null) file.put("previous_path", change.previousPath());
                file.putNull("before_digest");
                file.putNull("after_digest");
            });
            return mapper.writeValueAsString(proposal);
        } catch (Exception failure) {
            throw new IllegalArgumentException("Cannot bind patch proposal metadata: " + failure.getMessage(), failure);
        }
    }

    private static String normalize(String value) {
        String patch = value.strip();
        int fenceStart = patch.indexOf("```");
        if (fenceStart >= 0) {
            int firstNewline = patch.indexOf('\n', fenceStart);
            if (firstNewline >= 0) patch = patch.substring(firstNewline + 1);
            if (patch.endsWith("```")) patch = patch.substring(0, patch.length() - 3);
        }
        int diffStart = patch.indexOf("diff --git ");
        if (diffStart > 0) patch = patch.substring(diffStart);
        List<String> lines = new ArrayList<>(List.of(patch.split("\\n", -1)));
        for (int index = 0; index < lines.size(); index++) {
            Matcher header = HUNK_HEADER.matcher(lines.get(index));
            if (!header.matches()) continue;
            int oldLines = 0;
            int newLines = 0;
            int end = index + 1;
            while (end < lines.size() && !lines.get(end).startsWith("@@ ")
                    && !lines.get(end).startsWith("diff --git ")) {
                String line = lines.get(end);
                if (line.startsWith(" ")) {
                    oldLines++;
                    newLines++;
                } else if (line.startsWith("-")) {
                    oldLines++;
                } else if (line.startsWith("+")) {
                    newLines++;
                }
                end++;
            }
            lines.set(index, "@@ -" + range(header.group(1), oldLines)
                    + " +" + range(header.group(3), newLines) + " @@" + header.group(5));
        }
        return String.join("\n", lines).stripTrailing() + "\n";
    }

    private static String range(String start, int count) {
        return count == 1 ? start : start + ',' + count;
    }

    private static List<FileChange> changes(String patch) {
        String[] lines = patch.split("\\n");
        List<FileChange> changes = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].startsWith("diff --git ")) continue;
            Matcher header = DIFF_HEADER.matcher(lines[index]);
            if (!header.matches()) throw new IllegalArgumentException("Unsupported unified diff header");
            int end = index + 1;
            while (end < lines.length && !lines[end].startsWith("diff --git ")) end++;
            String oldPath = header.group(1);
            String path = header.group(2);
            String previousPath = null;
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
        if (changes.isEmpty()) throw new IllegalArgumentException("Patch contains no file diff");
        return List.copyOf(changes);
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private record FileChange(String path, String previousPath, String operation) {
    }
}
