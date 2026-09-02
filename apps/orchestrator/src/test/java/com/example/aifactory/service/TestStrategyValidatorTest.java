package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestStrategyValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TestStrategyValidator validator = new TestStrategyValidator();

    @Test
    void linksEveryTestCaseToKnownAcceptanceCriteriaAndCoversThemAll() throws Exception {
        JsonNode valid = strategy();
        assertThat(validator.validate(valid)).isSameAs(valid);

        var unknown = valid.deepCopy();
        ((tools.jackson.databind.node.ArrayNode) unknown.path("test_cases").get(0).path("covers_criteria"))
                .set(0, mapper.valueToTree("criterion-unknown"));
        assertThatThrownBy(() -> validator.validate(unknown)).hasMessageContaining("unknown criterion");

        var uncovered = valid.deepCopy();
        ((tools.jackson.databind.node.ArrayNode) uncovered.path("acceptance_criteria"))
                .add(mapper.readTree("""
                        {"criterion_id":"criterion-2","description":"Error is handled",
                         "source_reference_id":"requirement-1"}
                        """));
        assertThatThrownBy(() -> validator.validate(uncovered)).hasMessageContaining("uncovered");
    }

    private JsonNode strategy() throws Exception {
        Path fixture = Path.of(System.getProperty("user.dir"))
                .resolve("../../resources/multiagents/fixtures/golden-contracts-v1.json").normalize();
        return mapper.readTree(Files.readString(fixture)).path("documents").path("test-strategy-v1");
    }
}
