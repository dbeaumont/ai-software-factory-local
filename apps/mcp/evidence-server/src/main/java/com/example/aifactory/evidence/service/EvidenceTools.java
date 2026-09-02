package com.example.aifactory.evidence.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class EvidenceTools {
    private final EvidenceStore store;
    private final EvidenceReadAudit audit;
    public EvidenceTools(EvidenceStore store, EvidenceReadAudit audit) { this.store = store; this.audit = audit; }

    @Tool(name = "evidence.store", description = "Verify and immutably store one task-scoped evidence artifact")
    public StoredEvidence store(@ToolParam(description = "Contract version") String schema_version,
                                @ToolParam(description = "Task identifier") String task_id,
                                @ToolParam(description = "Attempt identifier") String attempt_id,
                                @ToolParam(description = "Evidence type") String type,
                                @ToolParam(description = "IANA media type") String media_type,
                                @ToolParam(description = "Base64 content") String content_base64,
                                @ToolParam(description = "Expected SHA-256") String digest,
                                @ToolParam(description = "Authorized workflow actor") String actor) throws Exception {
        if (!"1".equals(schema_version)) throw new SecurityException("unauthorized evidence request");
        EvidenceStore.StoredEvidence stored = store.store(task_id, attempt_id, type, media_type, content_base64, digest, actor);
        return new StoredEvidence(stored.uri(), stored.digest(), stored.status(), stored.mediaType(), stored.sizeBytes(),
                stored.classification(), stored.retainUntil(), stored.storedAt());
    }

    @Tool(name = "evidence.create_manifest", description = "Create one immutable manifest from already stored same-attempt evidence")
    public StoredManifest createManifest(@ToolParam(description = "Contract version") String schema_version,
                                         @ToolParam(description = "Task identifier") String task_id,
                                         @ToolParam(description = "Attempt identifier") String attempt_id,
                                         @ToolParam(description = "Registered repository identifier") String repository_id,
                                         @ToolParam(description = "Immutable source commit") String source_commit,
                                         @ToolParam(description = "Patch SHA-256") String patch_digest,
                                         @ToolParam(description = "Nine stored evidence references") java.util.Map<String, EvidenceReference> artifacts,
                                         @ToolParam(description = "Structured assurance policy decision") PolicyDecision policy_decision,
                                         @ToolParam(description = "Authorized workflow actor") String actor) throws Exception {
        if (!"1".equals(schema_version) || !"workflow".equals(actor)) throw new SecurityException("unauthorized manifest request");
        java.util.Map<String, EvidenceStore.EvidenceReference> stored = new java.util.LinkedHashMap<>();
        artifacts.forEach((name, ref) -> stored.put(name, new EvidenceStore.EvidenceReference(ref.uri(), ref.digest(), ref.status())));
        EvidenceStore.PolicyDecision decision = new EvidenceStore.PolicyDecision(policy_decision.schemaVersion(),
                policy_decision.taskId(), policy_decision.attemptId(), policy_decision.policyId(),
                policy_decision.policyVersion(), policy_decision.decision(), policy_decision.reasons(),
                policy_decision.inputDigests(), policy_decision.decidedAt());
        EvidenceStore.StoredManifest manifest = store.createManifest(task_id, attempt_id, repository_id, source_commit,
                patch_digest, stored, decision);
        return new StoredManifest(manifest.manifestId(), manifest.uri(), manifest.digest(), manifest.status(),
                manifest.classification(), manifest.retainUntil(), manifest.createdAt());
    }

    @Tool(name = "evidence.get_summary", description = "Return authorized evidence metadata without raw content")
    public EvidenceSummary getSummary(@ToolParam(description = "Contract version") String schema_version,
                                      @ToolParam(description = "Task identifier") String task_id,
                                      @ToolParam(description = "Evidence URI") String uri,
                                      @ToolParam(description = "Allowlisted workflow or agent role") String actor) throws Exception {
        if (!"1".equals(schema_version)) throw new IllegalArgumentException("unsupported schema version");
        EvidenceStore.ReadEvidence value = store.read(task_id, uri, actor, "summary", false);
        return new EvidenceSummary(value.uri(), value.type(), value.digest(), value.status(), value.classification(), value.sizeBytes());
    }

    @Tool(name = "evidence.read", description = "Explicitly read authorized raw evidence and emit an immutable audit event")
    public RawEvidence read(@ToolParam(description = "Contract version") String schema_version,
                            @ToolParam(description = "Task identifier") String task_id,
                            @ToolParam(description = "Evidence URI") String uri,
                            @ToolParam(description = "workflow or reviewer") String actor,
                            @ToolParam(description = "human-review or incident-investigation") String purpose) throws Exception {
        if (!"1".equals(schema_version)) throw new IllegalArgumentException("unsupported schema version");
        try {
            EvidenceStore.ReadEvidence value = store.read(task_id, uri, actor, purpose, true);
            audit.record(task_id, actor, purpose, uri, "ALLOWED");
            return new RawEvidence(value.uri(), value.type(), value.digest(), value.status(), value.classification(),
                    value.sizeBytes(), value.contentBase64());
        } catch (RuntimeException exception) {
            audit.record(task_id, actor == null ? "unknown" : actor, purpose == null ? "unknown" : purpose,
                    uri == null ? "invalid" : uri, "DENIED");
            throw exception;
        }
    }

    public record StoredEvidence(String uri, String digest, String status,
                                 @JsonProperty("media_type") String mediaType,
                                 @JsonProperty("size_bytes") long sizeBytes,
                                 String classification,
                                 @JsonProperty("retain_until") java.time.Instant retainUntil,
                                 @JsonProperty("stored_at") java.time.Instant storedAt) {}
    public record EvidenceReference(String uri, String digest, String status) {}
    public record PolicyDecision(@JsonProperty("schema_version") String schemaVersion,
                                 @JsonProperty("task_id") String taskId,
                                 @JsonProperty("attempt_id") String attemptId,
                                 @JsonProperty("policy_id") String policyId,
                                 @JsonProperty("policy_version") String policyVersion,
                                 String decision, java.util.List<String> reasons,
                                 @JsonProperty("input_digests") java.util.Map<String, String> inputDigests,
                                 @JsonProperty("decided_at") java.time.Instant decidedAt) {}
    public record StoredManifest(@JsonProperty("manifest_id") String manifestId, String uri, String digest,
                                 String status, String classification,
                                 @JsonProperty("retain_until") java.time.Instant retainUntil,
                                 @JsonProperty("created_at") java.time.Instant createdAt) {}
    public record EvidenceSummary(String uri, String type, String digest, String status, String classification,
                                  @JsonProperty("size_bytes") long sizeBytes) {}
    public record RawEvidence(String uri, String type, String digest, String status, String classification,
                              @JsonProperty("size_bytes") long sizeBytes,
                              @JsonProperty("content_base64") String contentBase64) {}
}
