package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiAgentContractValidatorTest {
    private final MultiAgentContractValidator validator = new MultiAgentContractValidator(new ObjectMapper());

    @Test
    void loadsEveryPinnedContractAndRejectsAnInvalidDocument() {
        assertThat(validator.contracts()).hasSize(18);
        validator.contracts().forEach(contract -> assertThatThrownBy(() -> validator.validate(contract, "{}"))
                .isInstanceOf(MultiAgentContractValidator.ContractValidationException.class)
                .hasMessageContaining("violates the local schema"));
    }

    @Test
    void rejectsUnknownContractAndMalformedJson() {
        assertThatThrownBy(() -> validator.validate("unknown-v1", "{}"))
                .hasMessageContaining("unknown contract");
        assertThatThrownBy(() -> validator.validate("delegation-plan-v1", "{"))
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsAdditionalFieldsUnknownRolesMissingIdsAndCrossTaskReferences() throws Exception {
        String event = """
                {"schema_version":"1","event_id":"event-1","task_id":"task-1","attempt_id":"attempt-1",
                 "specialist_task_id":"specialist-1","node_id":"node-1","role":"developer","sequence":0,
                 "event_type":"STARTED","previous_state":"QUEUED","state":"RUNNING",
                 "usage_delta":{"turns":0,"input_tokens":0,"output_tokens":0,"cost_micros":null,"tool_calls":0,"duration_millis":0},
                 "reason":"started","occurred_at":"2026-09-02T10:00:00Z"}
                """;
        var context = new MultiAgentContractValidator.ContractContext("task-1", "attempt-1",
                Set.of("specialist-1", "node-1"));
        var valid = new ObjectMapper().readTree(event);
        validator.validate("agent-run-event-v1", valid, context);

        var extra = valid.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) extra).put("unexpected", true);
        assertThatThrownBy(() -> validator.validate("agent-run-event-v1", extra, context))
                .hasMessageContaining("violates the local schema");

        var missingId = valid.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) missingId).remove("task_id");
        assertThatThrownBy(() -> validator.validate("agent-run-event-v1", missingId, context))
                .hasMessageContaining("violates the local schema");

        var unknownRole = valid.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) unknownRole).put("role", "admin-agent");
        assertThatThrownBy(() -> validator.validate("agent-run-event-v1", unknownRole, context))
                .hasMessageContaining("unknown role");

        var foreignReference = valid.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) foreignReference).put("node_id", "foreign-node");
        assertThatThrownBy(() -> validator.validate("agent-run-event-v1", foreignReference, context))
                .hasMessageContaining("reference outside task");

        var foreignTask = valid.deepCopy();
        ((tools.jackson.databind.node.ObjectNode) foreignTask).put("task_id", "task-2");
        assertThatThrownBy(() -> validator.validate("agent-run-event-v1", foreignTask, context))
                .hasMessageContaining("outside the current task context");
    }

    @Test
    void validatesGoldenAndRejectsNegativeFuzzedAndOversizedFixturesForEveryContract() throws Exception {
        JsonNode fixtures = new ObjectMapper().readTree(Files.readString(fixturePath())).path("documents");
        assertThat(validator.contracts()).hasSize(18);
        for (String contract : validator.contracts()) {
            JsonNode golden = fixtures.path(contract);
            assertThat(golden.isObject()).as(contract + " golden fixture").isTrue();
            validator.validate(contract, golden);

            var additional = golden.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) additional).put("unexpected", true);
            assertThatThrownBy(() -> validator.validate(contract, additional)).as(contract + " additional field")
                    .isInstanceOf(MultiAgentContractValidator.ContractValidationException.class);

            var missingId = golden.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) missingId).remove("task_id");
            assertThatThrownBy(() -> validator.validate(contract, missingId)).as(contract + " missing id")
                    .isInstanceOf(MultiAgentContractValidator.ContractValidationException.class);

            var fuzzed = golden.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) fuzzed).put("task_id", "x".repeat(4096));
            assertThatThrownBy(() -> validator.validate(contract, fuzzed)).as(contract + " fuzzed id")
                    .isInstanceOf(MultiAgentContractValidator.ContractValidationException.class);

            String oversized = "{\"padding\":\"" + "x".repeat(1_048_576) + "\"}";
            assertThatThrownBy(() -> validator.validate(contract, oversized)).as(contract + " size limit")
                    .hasMessageContaining("maximum size");
        }
    }

    @Test
    void supervisorPlanRequiresBoundCitationsAssumptionsRisksBudgetsAndSuccessCriteria() throws Exception {
        JsonNode plan = new ObjectMapper().readTree(Files.readString(fixturePath()))
                .path("documents").path("delegation-plan-v1");
        validator.validate("delegation-plan-v1", plan,
                new MultiAgentContractValidator.ContractContext("task-1", "attempt-1", Set.of("context-1")));

        for (String required : java.util.List.of("citations", "assumptions", "risks")) {
            var missing = plan.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) missing).remove(required);
            assertThatThrownBy(() -> validator.validate("delegation-plan-v1", missing))
                    .as("missing " + required).hasMessageContaining("violates");
        }
        assertThatThrownBy(() -> validator.validate("delegation-plan-v1", plan,
                new MultiAgentContractValidator.ContractContext("task-1", "attempt-1", Set.of("another-ref"))))
                .hasMessageContaining("citation outside task");

        for (String required : java.util.List.of("budget", "success_criteria")) {
            var missing = plan.deepCopy();
            ((tools.jackson.databind.node.ObjectNode) missing.path("nodes").get(0)).remove(required);
            assertThatThrownBy(() -> validator.validate("delegation-plan-v1", missing))
                    .as("missing node " + required).hasMessageContaining("violates");
        }
    }

    private static Path fixturePath() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        for (Path candidate : java.util.List.of(
                workingDirectory.resolve("resources/multiagents/fixtures/golden-contracts-v1.json"),
                workingDirectory.resolve("../../resources/multiagents/fixtures/golden-contracts-v1.json").normalize())) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Cannot find multi-agent golden fixtures");
    }
}
