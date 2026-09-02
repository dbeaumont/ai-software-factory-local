package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Builds the reviewer's untrusted JSON input exclusively from workflow-owned typed references. */
@Component
public final class IndependentReviewInputBuilder {
    private final ObjectMapper mapper;

    public IndependentReviewInputBuilder(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode build(IndependentReviewBundle bundle, String taskId, String attemptId, String sourceCommit) {
        if (bundle == null || !bundle.boundTo(taskId, attemptId, sourceCommit)) {
            throw new SecurityException("Independent review bundle is outside workflow lineage");
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("schema_version", "1").put("task_id", taskId).put("attempt_id", attemptId)
                .put("source_commit", sourceCommit);
        IndependentReviewBundle.ConsolidatedPatch patch = bundle.consolidatedPatch();
        ObjectNode patchNode = root.putObject("consolidated_patch");
        patchNode.put("patch_id", patch.patchId()).put("uri", patch.uri()).put("digest", patch.digest());
        ArrayNode files = patchNode.putArray("changed_files");
        patch.changedFiles().forEach(files::add);
        IndependentReviewBundle.FinalManifest manifest = bundle.finalManifest();
        root.putObject("final_manifest").put("manifest_id", manifest.manifestId())
                .put("uri", manifest.uri()).put("digest", manifest.digest());
        ArrayNode results = root.putArray("reviewed_results");
        bundle.reviewedResults().forEach(result -> results.addObject()
                .put("result_id", result.resultId()).put("role", result.role())
                .put("uri", result.uri()).put("digest", result.digest()));
        ArrayNode contradictions = root.putArray("contradictions");
        bundle.contradictions().forEach(contradiction -> contradictions.addObject()
                .put("contradiction_id", contradiction.contradictionId())
                .put("status", contradiction.status()).put("uri", contradiction.uri())
                .put("digest", contradiction.digest()));
        return root;
    }
}
