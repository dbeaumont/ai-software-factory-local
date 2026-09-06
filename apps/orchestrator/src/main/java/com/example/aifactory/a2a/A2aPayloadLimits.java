package com.example.aifactory.a2a;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/** Fail-fast protocol limits applied before business-schema validation. */
public final class A2aPayloadLimits {
    public static final int MAX_REQUEST_BYTES = 1_048_576;
    public static final int MAX_PARTS = 16;
    public static final int MAX_HISTORY_ENTRIES = 50;
    public static final int MAX_ARTIFACTS = 16;
    public static final int MAX_EVIDENCE_REFERENCES = 32;
    public static final int MAX_JSON_DEPTH = 32;
    public static final Duration TASK_RETENTION = Duration.ofDays(30);

    private final ObjectMapper boundedMapper = new ObjectMapper(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxDocumentLength(MAX_REQUEST_BYTES).maxNestingDepth(MAX_JSON_DEPTH)
                    .maxTokenCount(100_000).maxStringLength(262_144).maxNameLength(256).build())
            .build());

    public JsonNode parse(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_REQUEST_BYTES) {
            throw new PayloadLimitException("request_bytes", "A2A request size is outside limits");
        }
        try { return boundedMapper.readTree(body); }
        catch (Exception exception) {
            throw new PayloadLimitException("json_structure", "A2A JSON exceeds structural limits", exception);
        }
    }

    public void validate(A2aContracts.SendCommand command) {
        if (command.parts().size() > MAX_PARTS) {
            throw new PayloadLimitException("parts", "A2A message has too many parts");
        }
    }

    public void validate(A2aContracts.TaskQuery query) {
        if (query.historyLength() > MAX_HISTORY_ENTRIES) {
            throw new PayloadLimitException("history", "A2A history request exceeds its limit");
        }
    }

    public void validate(A2aContracts.TaskSnapshot snapshot) {
        if (snapshot.artifacts().size() > MAX_ARTIFACTS) {
            throw new PayloadLimitException("artifacts", "A2A task has too many artifacts");
        }
        snapshot.artifacts().forEach(artifact -> {
            if (artifact.parts().size() > MAX_PARTS) {
                throw new PayloadLimitException("parts", "A2A artifact has too many parts");
            }
        });
    }

    public static final class PayloadLimitException extends IllegalArgumentException {
        private final String limit;
        PayloadLimitException(String limit, String message) { super(message); this.limit = limit; }
        PayloadLimitException(String limit, String message, Throwable cause) {
            super(message, cause); this.limit = limit;
        }
        public String limit() { return limit; }
    }
}
