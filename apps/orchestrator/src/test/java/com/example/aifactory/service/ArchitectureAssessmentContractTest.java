package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureAssessmentContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final MultiAgentContractValidator validator = new MultiAgentContractValidator(mapper);

    @Test
    void requiresCodeScopesConstraintsApiDataImpactsAndHumanDecisions() throws Exception {
        JsonNode assessment = mapper.readTree(Files.readString(fixturePath()))
                .path("documents").path("architecture-assessment-v1");
        validator.validate("architecture-assessment-v1", assessment,
                new MultiAgentContractValidator.ContractContext(
                        "task-1", "attempt-1", Set.of("specialist-1")));

        for (String required : List.of("recommended_code_scopes", "constraints", "api_impacts",
                "data_impacts", "human_decisions")) {
            var missing = assessment.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) missing).remove(required);
            assertThatThrownBy(() -> validator.validate("architecture-assessment-v1", missing))
                    .as("missing " + required).hasMessageContaining("violates");
        }
        var unboundedScope = assessment.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) unboundedScope.path("recommended_code_scopes").get(0))
                .remove("write_paths");
        assertThatThrownBy(() -> validator.validate("architecture-assessment-v1", unboundedScope))
                .hasMessageContaining("violates");
    }

    private static Path fixturePath() {
        return Path.of(System.getProperty("user.dir"))
                .resolve("../../resources/multiagents/fixtures/golden-contracts-v1.json").normalize();
    }
}
