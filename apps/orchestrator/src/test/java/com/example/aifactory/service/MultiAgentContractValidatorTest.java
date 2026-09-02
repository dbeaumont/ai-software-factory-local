package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiAgentContractValidatorTest {
    private final MultiAgentContractValidator validator = new MultiAgentContractValidator(new ObjectMapper());

    @Test
    void loadsEveryPinnedContractAndRejectsAnInvalidDocument() {
        assertThat(validator.contracts()).hasSize(15);
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
}
