package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelegationPlanValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DelegationPlanValidator validator = new DelegationPlanValidator();

    @Test
    void acceptsAcyclicPlanWithKnownParentsAndDependencies() throws Exception {
        JsonNode plan = nodes("""
                {"node_id":"architecture","parent_node_id":null,"depends_on":[],"success_criteria":["assessment produced"],"stop_condition":"SUCCESS_CRITERIA_MET"},
                {"node_id":"code","parent_node_id":null,"depends_on":["architecture"],"success_criteria":["patch proposed"],"stop_condition":"BLOCKED_OR_ESCALATE"},
                {"node_id":"developer","parent_node_id":"code","depends_on":["architecture"],"success_criteria":["scope complete"],"stop_condition":"DEADLINE_REACHED"}
                """);
        assertThat(validator.validate(plan)).isSameAs(plan);
    }

    @Test
    void rejectsCyclesOrphansSelfReferencesAndDuplicateNodes() {
        assertThatThrownBy(() -> validator.validate(nodes("""
                {"node_id":"a","parent_node_id":null,"depends_on":["b"],"success_criteria":["done"],"stop_condition":"SUCCESS_CRITERIA_MET"},
                {"node_id":"b","parent_node_id":null,"depends_on":["a"],"success_criteria":["done"],"stop_condition":"SUCCESS_CRITERIA_MET"}
                """))).hasMessageContaining("cycle");
        assertThatThrownBy(() -> validator.validate(nodes("""
                {"node_id":"a","parent_node_id":null,"depends_on":["missing"],"success_criteria":["done"],"stop_condition":"SUCCESS_CRITERIA_MET"}
                """))).hasMessageContaining("orphan");
        assertThatThrownBy(() -> validator.validate(nodes("""
                {"node_id":"a","parent_node_id":"a","depends_on":[],"success_criteria":["done"],"stop_condition":"SUCCESS_CRITERIA_MET"}
                """))).hasMessageContaining("self dependency");
        assertThatThrownBy(() -> validator.validate(nodes("""
                {"node_id":"a","parent_node_id":null,"depends_on":[],"success_criteria":["done"],"stop_condition":"SUCCESS_CRITERIA_MET"},
                {"node_id":"a","parent_node_id":null,"depends_on":[],"success_criteria":["done"],"stop_condition":"SUCCESS_CRITERIA_MET"}
                """))).hasMessageContaining("duplicate");
    }

    @Test
    void rejectsMissingSuccessCriteriaOrUnknownStopCondition() {
        assertThatThrownBy(() -> validator.validate(nodes("""
                {"node_id":"a","parent_node_id":null,"depends_on":[],"success_criteria":[],"stop_condition":"SUCCESS_CRITERIA_MET"}
                """))).hasMessageContaining("success criteria");
        assertThatThrownBy(() -> validator.validate(nodes("""
                {"node_id":"a","parent_node_id":null,"depends_on":[],"success_criteria":["done"],"stop_condition":"WHEN_MODEL_DECIDES"}
                """))).hasMessageContaining("stop condition");
    }

    private JsonNode nodes(String nodes) throws Exception {
        return mapper.readTree("{\"nodes\":[" + nodes + "]}");
    }
}
