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
                {"node_id":"architecture","parent_node_id":null,"depends_on":[]},
                {"node_id":"code","parent_node_id":null,"depends_on":["architecture"]},
                {"node_id":"developer","parent_node_id":"code","depends_on":["architecture"]}
                """);
        assertThat(validator.validate(plan)).isSameAs(plan);
    }

    @Test
    void rejectsCyclesOrphansSelfReferencesAndDuplicateNodes() {
        assertThatThrownBy(() -> validator.validate(nodes("""
                {"node_id":"a","parent_node_id":null,"depends_on":["b"]},
                {"node_id":"b","parent_node_id":null,"depends_on":["a"]}
                """))).hasMessageContaining("cycle");
        assertThatThrownBy(() -> validator.validate(nodes("""
                {"node_id":"a","parent_node_id":null,"depends_on":["missing"]}
                """))).hasMessageContaining("orphan");
        assertThatThrownBy(() -> validator.validate(nodes("""
                {"node_id":"a","parent_node_id":"a","depends_on":[]}
                """))).hasMessageContaining("self dependency");
        assertThatThrownBy(() -> validator.validate(nodes("""
                {"node_id":"a","parent_node_id":null,"depends_on":[]},
                {"node_id":"a","parent_node_id":null,"depends_on":[]}
                """))).hasMessageContaining("duplicate");
    }

    private JsonNode nodes(String nodes) throws Exception {
        return mapper.readTree("{\"nodes\":[" + nodes + "]}");
    }
}
