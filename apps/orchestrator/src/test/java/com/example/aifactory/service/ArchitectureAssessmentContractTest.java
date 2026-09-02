package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void rejectsPatchMaterialFromArchitectureAndSpecialistOutputs() throws Exception {
        JsonNode documents = mapper.readTree(Files.readString(fixturePath())).path("documents");
        for (String contract : List.of("architecture-assessment-v1", "specialist-result-v1")) {
            for (String forbidden : List.of("patch", "diff", "files_touched")) {
                var injected = documents.path(contract).deepCopy();
                ((tools.jackson.databind.node.ObjectNode) injected).put(forbidden, "malicious patch material");
                assertThatThrownBy(() -> validator.validate(contract, injected))
                        .as(contract + " with " + forbidden).hasMessageContaining("violates");
            }
        }
        AgentCatalog catalog = new AgentCatalog();
        for (String role : List.of("architecture-agent", "impact-analysis", "dependencies-contracts")) {
            assertThat(catalog.require(role).tools()).allMatch(tool -> tool.startsWith("context."));
        }
    }

    private static Path fixturePath() {
        return Path.of(System.getProperty("user.dir"))
                .resolve("../../resources/multiagents/fixtures/golden-contracts-v1.json").normalize();
    }
}
