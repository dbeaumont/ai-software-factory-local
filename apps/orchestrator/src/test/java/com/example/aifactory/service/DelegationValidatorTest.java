package com.example.aifactory.service;

import com.example.aifactory.config.DelegationPolicyProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelegationValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DelegationValidator validator = new DelegationValidator(new AgentCatalog(), new DelegationPlanValidator());

    @Test
    void acceptsRolesHierarchyScopesDependenciesAndBudgetsInsideHostLimits() throws Exception {
        JsonNode plan = plan("src/main", 1000, "developer", "code-agent");
        assertThat(validator.validate(plan, limits())).isSameAs(plan);
    }

    @Test
    void rejectsUnknownRoleScopeEscapeBudgetAndInvalidParent() throws Exception {
        assertThatThrownBy(() -> validator.validate(plan("src/main", 1000, "admin-agent", "code-agent"), limits()))
                .hasMessageContaining("Unknown agent role");
        assertThatThrownBy(() -> validator.validate(plan("infrastructure", 1000, "developer", "code-agent"), limits()))
                .hasMessageContaining("write scope outside");
        assertThatThrownBy(() -> validator.validate(plan("src/main", 6000, "developer", "code-agent"), limits()))
                .hasMessageContaining("token budget");
        assertThatThrownBy(() -> validator.validate(plan("src/main", 1000, "developer", "architecture-agent"), limits()))
                .hasMessageContaining("invalid parent");
    }

    @Test
    void callerCannotRaiseTheHostDepthCeiling() throws Exception {
        DelegationValidator strict = new DelegationValidator(new AgentCatalog(), new DelegationPlanValidator(),
                new DelegationPolicyProperties(1, 4));
        DelegationValidator.Limits permissiveCaller = new DelegationValidator.Limits(
                Set.of("architecture-agent", "code-agent", "developer"), List.of("src"), List.of("src"),
                100, 100, 10_000, 1_000);

        assertThatThrownBy(() -> strict.validate(plan("src/main", 1000, "developer", "code-agent"),
                permissiveCaller)).hasMessageContaining("maximum depth");
    }

    @Test
    void callerCannotRaiseTheHostFanOutCeiling() throws Exception {
        DelegationValidator strict = new DelegationValidator(new AgentCatalog(), new DelegationPlanValidator(),
                new DelegationPolicyProperties(2, 1));
        DelegationValidator.Limits permissiveCaller = new DelegationValidator.Limits(
                Set.of("architecture-agent", "code-agent"), List.of("src"), List.of("src"),
                100, 100, 10_000, 1_000);
        JsonNode roots = mapper.readTree("""
                {"nodes":[
                  {"node_id":"architecture","role":"architecture-agent","parent_node_id":null,"depends_on":[],
                   "scope":{"read_paths":["src"],"write_paths":[]},
                   "budget":{"max_tokens":100,"max_cost_micros":10},
                   "success_criteria":["done"],"stop_condition":"SUCCESS_CRITERIA_MET"},
                  {"node_id":"code","role":"code-agent","parent_node_id":null,"depends_on":[],
                   "scope":{"read_paths":["src"],"write_paths":[]},
                   "budget":{"max_tokens":100,"max_cost_micros":10},
                   "success_criteria":["done"],"stop_condition":"SUCCESS_CRITERIA_MET"}
                ]}
                """);

        assertThatThrownBy(() -> strict.validate(roots, permissiveCaller))
                .hasMessageContaining("maximum fan-out");
    }

    private JsonNode plan(String writePath, int childTokens, String childRole, String parentRole) throws Exception {
        return mapper.readTree("""
                {"nodes":[
                  {"node_id":"parent","role":"%s","parent_node_id":null,"depends_on":[],
                   "scope":{"read_paths":["src"],"write_paths":[]},"budget":{"max_tokens":1000,"max_cost_micros":100},
                   "success_criteria":["planned"],"stop_condition":"SUCCESS_CRITERIA_MET"},
                  {"node_id":"child","role":"%s","parent_node_id":"parent","depends_on":["parent"],
                   "scope":{"read_paths":["src"],"write_paths":["%s"]},"budget":{"max_tokens":%d,"max_cost_micros":100},
                   "success_criteria":["done"],"stop_condition":"SUCCESS_CRITERIA_MET"}
                ]}
                """.formatted(parentRole, childRole, writePath, childTokens));
    }

    private static DelegationValidator.Limits limits() {
        return new DelegationValidator.Limits(Set.of("architecture-agent", "code-agent", "developer"), List.of("src"), List.of("src"),
                2, 4, 5000, 1000);
    }
}
