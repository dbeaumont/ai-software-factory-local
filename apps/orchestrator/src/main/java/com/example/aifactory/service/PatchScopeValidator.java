package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Validates patch metadata against host-issued scope before any sandbox validation can be scheduled. */
@Component
public final class PatchScopeValidator {
    public void validateDeveloper(JsonNode codeTask, JsonNode proposal) {
        JsonNode scope = codeTask.path("scope");
        validate(proposal, strings(scope.path("write_paths")), strings(scope.path("forbidden_paths")),
                scope.path("max_changed_files").asInt(), scope.path("max_patch_bytes").asLong());
    }

    public void validateRepair(JsonNode repairTask, JsonNode proposal) {
        validate(proposal, strings(repairTask.path("target_paths")), List.of(),
                repairTask.path("target_paths").size(), 1_048_576);
    }

    private static void validate(JsonNode proposal, List<String> allowed, List<String> forbidden,
                                 int maxFiles, long maxPatchBytes) {
        JsonNode touched = proposal.path("files_touched");
        if (touched.size() > maxFiles) throw invalid("changed file limit exceeded");
        if (proposal.path("diff_artifact").path("size_bytes").asLong() > maxPatchBytes) {
            throw invalid("patch byte limit exceeded");
        }
        for (JsonNode file : touched) {
            requireAllowed(file.path("path").asText(), allowed, forbidden);
            if (file.has("previous_path")) {
                requireAllowed(file.path("previous_path").asText(), allowed, forbidden);
            }
        }
    }

    private static void requireAllowed(String path, List<String> allowed, List<String> forbidden) {
        if (allowed.stream().noneMatch(root -> within(path, root))) {
            throw invalid("file outside assigned write scope: " + path);
        }
        if (forbidden.stream().anyMatch(root -> within(path, root))) {
            throw invalid("file is explicitly forbidden: " + path);
        }
    }

    private static boolean within(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static SecurityException invalid(String reason) {
        return new SecurityException("Patch proposal rejected before sandbox: " + reason);
    }
}
