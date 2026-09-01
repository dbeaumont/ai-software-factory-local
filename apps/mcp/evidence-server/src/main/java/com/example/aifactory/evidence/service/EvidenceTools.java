package com.example.aifactory.evidence.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class EvidenceTools {
    private final EvidenceStore store;
    public EvidenceTools(EvidenceStore store) { this.store = store; }

    @Tool(name = "evidence.store", description = "Verify and immutably store one task-scoped evidence artifact")
    public StoredEvidence store(@ToolParam(description = "Contract version") String schema_version,
                                @ToolParam(description = "Task identifier") String task_id,
                                @ToolParam(description = "Attempt identifier") String attempt_id,
                                @ToolParam(description = "Evidence type") String type,
                                @ToolParam(description = "IANA media type") String media_type,
                                @ToolParam(description = "Base64 content") String content_base64,
                                @ToolParam(description = "Expected SHA-256") String digest,
                                @ToolParam(description = "Authorized workflow actor") String actor) throws Exception {
        if (!"1".equals(schema_version) || !"workflow".equals(actor)) throw new SecurityException("unauthorized evidence request");
        EvidenceStore.StoredEvidence stored = store.store(task_id, attempt_id, type, media_type, content_base64, digest);
        return new StoredEvidence(stored.uri(), stored.digest(), stored.status(), stored.mediaType(), stored.sizeBytes(), stored.storedAt());
    }

    public record StoredEvidence(String uri, String digest, String status,
                                 @JsonProperty("media_type") String mediaType,
                                 @JsonProperty("size_bytes") long sizeBytes,
                                 @JsonProperty("stored_at") java.time.Instant storedAt) {}
}
