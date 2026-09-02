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
                    || !expected.digest().equals(execution.path("digest").asText())
                    || !expected.executionStatus().equals(execution.path("status").asText())) {
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
        if ("PASSED".equals(assessment.path("status").asText())) {
            if (!observed.equals(byExecution.keySet())) {
                throw invalid("PASSED requires every supplied execution");
            }
            if (supplied.stream().anyMatch(value -> !"PASSED".equals(value.executionStatus())
                    || !"COMPLETE".equals(value.evidenceStatus()) || value.outputTruncated())) {
                throw invalid("PASSED requires complete deterministic evidence");
            }
            if (!assessment.path("missing_evidence").isEmpty()) {
                throw invalid("PASSED cannot declare missing evidence");
            }
            for (ExecutionEvidence expected : supplied) {
                boolean cited = false;
                for (JsonNode reference : assessment.path("evidence")) {
                    cited |= expected.uri().equals(reference.path("uri").asText())
                            && expected.digest().equals(reference.path("digest").asText());
                }
                if (!cited) throw invalid("PASSED does not cite execution evidence " + expected.executionId());
            }
            JsonNode totals = assessment.path("totals");
            long tests = totals.path("tests").asLong();
            long accounted = totals.path("passed").asLong() + totals.path("failed").asLong()
                    + totals.path("skipped").asLong();
            if (tests != accounted || totals.path("failed").asLong() != 0) {
                throw invalid("PASSED totals are inconsistent");
            }
        }
        return assessment;
    }

    private static SecurityException invalid(String reason) {
        return new SecurityException("Test Evidence rejected: " + reason);
    }

    public record ExecutionEvidence(String executionId, String uri, String digest, String executionStatus,
                                    String evidenceStatus, boolean outputTruncated) {
        public ExecutionEvidence {
            if (executionId == null || executionId.isBlank() || uri == null || !uri.startsWith("evidence://")
                    || digest == null || !digest.matches("[0-9a-f]{64}")
                    || !Set.of("PASSED", "FAILED", "ERROR", "CANCELLED").contains(executionStatus)
                    || !Set.of("COMPLETE", "PARTIAL", "MISSING", "ALTERED").contains(evidenceStatus)) {
                throw new IllegalArgumentException("Invalid workflow test evidence reference");
            }
        }
    }
}
