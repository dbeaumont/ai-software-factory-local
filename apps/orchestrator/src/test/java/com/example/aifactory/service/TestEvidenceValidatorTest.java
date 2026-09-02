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
                "execution-1", "evidence://task-1/tests", "b".repeat(64));
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

    private JsonNode assessment() throws Exception {
        Path fixture = Path.of(System.getProperty("user.dir"))
                .resolve("../../resources/multiagents/fixtures/golden-contracts-v1.json").normalize();
        return mapper.readTree(Files.readString(fixture)).path("documents").path("test-assessment-v1");
    }
}
