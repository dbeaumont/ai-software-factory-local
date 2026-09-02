package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestEvidenceValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TestEvidenceValidator validator = new TestEvidenceValidator();

    @Test
    void acceptsOnlyExactExecutionEvidenceSuppliedByWorkflow() throws Exception {
        JsonNode assessment = assessment();
        TestEvidenceValidator.ExecutionEvidence supplied = new TestEvidenceValidator.ExecutionEvidence(
                "execution-1", "evidence://task-1/tests", "b".repeat(64),
                "PASSED", "COMPLETE", false);
        assertThat(validator.validate(assessment, Set.of(supplied))).isSameAs(assessment);

        var foreignExecution = assessment.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) foreignExecution.path("executions").get(0))
                .put("execution_id", "execution-foreign");
        assertThatThrownBy(() -> validator.validate(foreignExecution, Set.of(supplied)))
                .isInstanceOf(SecurityException.class).hasMessageContaining("not supplied");

        var alteredDigest = assessment.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) alteredDigest.path("executions").get(0))
                .put("digest", "d".repeat(64));
        assertThatThrownBy(() -> validator.validate(alteredDigest, Set.of(supplied)))
                .isInstanceOf(SecurityException.class).hasMessageContaining("not supplied");
    }

    @Test
    void refusesPassedWithoutCompleteDeterministicEvidence() throws Exception {
        JsonNode assessment = assessment();
        TestEvidenceValidator.ExecutionEvidence partial = new TestEvidenceValidator.ExecutionEvidence(
                "execution-1", "evidence://task-1/tests", "b".repeat(64),
                "PASSED", "PARTIAL", true);
        assertThatThrownBy(() -> validator.validate(assessment, Set.of(partial)))
                .isInstanceOf(SecurityException.class).hasMessageContaining("complete deterministic evidence");

        TestEvidenceValidator.ExecutionEvidence complete = new TestEvidenceValidator.ExecutionEvidence(
                "execution-1", "evidence://task-1/tests", "b".repeat(64),
                "PASSED", "COMPLETE", false);
        var missingCitation = assessment.deepCopy();
        ((tools.jackson.databind.node.ArrayNode) missingCitation.path("evidence")).removeAll();
        assertThatThrownBy(() -> validator.validate(missingCitation, Set.of(complete)))
                .isInstanceOf(SecurityException.class).hasMessageContaining("does not cite");

        var missingEvidence = assessment.deepCopy();
        ((tools.jackson.databind.node.ArrayNode) missingEvidence.path("missing_evidence"))
                .add("integration logs");
        assertThatThrownBy(() -> validator.validate(missingEvidence, Set.of(complete)))
                .isInstanceOf(SecurityException.class).hasMessageContaining("missing evidence");
    }

    private JsonNode assessment() throws Exception {
        Path fixture = Path.of(System.getProperty("user.dir"))
                .resolve("../../resources/multiagents/fixtures/golden-contracts-v1.json").normalize();
        return mapper.readTree(Files.readString(fixture)).path("documents").path("test-assessment-v1");
    }
}
