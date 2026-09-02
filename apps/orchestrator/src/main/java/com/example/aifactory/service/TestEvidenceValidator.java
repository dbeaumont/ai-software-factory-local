package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Binds a Test Evidence conclusion to the exact deterministic executions supplied by the workflow. */
@Component
public final class TestEvidenceValidator {
    public JsonNode validate(JsonNode assessment, Set<ExecutionEvidence> supplied) {
        Map<String, ExecutionEvidence> byExecution = new HashMap<>();
        for (ExecutionEvidence evidence : supplied) {
            if (byExecution.putIfAbsent(evidence.executionId(), evidence) != null) {
                throw invalid("duplicate supplied execution " + evidence.executionId());
            }
        }
        Set<String> observed = new HashSet<>();
        for (JsonNode execution : assessment.path("executions")) {
            String id = execution.path("execution_id").asText();
            ExecutionEvidence expected = byExecution.get(id);
            if (expected == null || !expected.uri().equals(execution.path("evidence_uri").asText())
                    || !expected.digest().equals(execution.path("digest").asText())) {
                throw invalid("execution was not supplied by workflow: " + id);
            }
            if (!observed.add(id)) throw invalid("execution is repeated: " + id);
        }
        for (JsonNode reference : assessment.path("evidence")) {
            boolean suppliedReference = supplied.stream().anyMatch(value ->
                    value.uri().equals(reference.path("uri").asText())
                            && value.digest().equals(reference.path("digest").asText()));
            if (!suppliedReference) throw invalid("evidence reference was not supplied by workflow");
        }
        return assessment;
    }

    private static SecurityException invalid(String reason) {
        return new SecurityException("Test Evidence rejected: " + reason);
    }

    public record ExecutionEvidence(String executionId, String uri, String digest) {
        public ExecutionEvidence {
            if (executionId == null || executionId.isBlank() || uri == null || !uri.startsWith("evidence://")
                    || digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid workflow test evidence reference");
            }
        }
    }
}
