package com.example.aifactory.service;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Set;

/** Validates normalized scanner findings and their exact workflow-supplied evidence binding. */
@Component
public final class SecurityFindingsInputValidator {
    private final ObjectMapper objectMapper;
    private final Schema schema;

    public SecurityFindingsInputValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
                "mcp/schemas/vulnerability-result-v1.schema.json")) {
            if (input == null) throw new IllegalStateException("Missing vulnerability result schema");
            this.schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(input);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load vulnerability result schema", exception);
        }
    }

    public JsonNode validate(String document, Context context) {
        JsonNode findings;
        try {
            findings = objectMapper.readTree(document);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Normalized security findings are not valid JSON", exception);
        }
        if (!schema.validate(findings).isEmpty()) {
            throw new IllegalArgumentException("Normalized security findings violate vulnerability-result-v1");
        }
        if (!context.taskId().equals(findings.path("task_id").asText())
                || !context.attemptId().equals(findings.path("attempt_id").asText())
                || !context.sourceCommit().equals(findings.path("source_commit").asText())) {
            throw new SecurityException("Normalized security findings are outside workflow lineage");
        }
        JsonNode evidence = findings.path("evidence");
        boolean supplied = context.evidence().stream().anyMatch(reference ->
                reference.uri().equals(evidence.path("uri").asText())
                        && reference.digest().equals(evidence.path("digest").asText()));
        if (!supplied || !"COMPLETE".equals(evidence.path("status").asText())) {
            throw new SecurityException("Normalized security findings lack complete supplied evidence");
        }
        return findings;
    }

    public record Context(String taskId, String attemptId, String sourceCommit,
                          Set<EvidenceReference> evidence) {
        public Context {
            evidence = Set.copyOf(evidence);
        }
    }

    public record EvidenceReference(String uri, String digest) {
        public EvidenceReference {
            if (uri == null || !uri.startsWith("evidence://")
                    || digest == null || !digest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid security evidence reference");
            }
        }
    }
}
