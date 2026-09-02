package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRoleIsolationTest {
    @Test
    void everyHierarchicalAgentRefusesEveryToolOutsideItsCatalogPerimeter() {
        AgentCatalog catalog = new AgentCatalog();
        ToolPermissionMatrix permissions = ToolPermissionMatrix.readOnlyAgents();
        Set<String> universe = catalog.roles().values().stream().flatMap(role -> role.tools().stream())
                .collect(Collectors.toUnmodifiableSet());
        Set<String> agents = catalog.roles().keySet().stream().filter(role -> !"workflow".equals(role))
                .collect(Collectors.toUnmodifiableSet());

        assertThat(agents).hasSize(14);
        for (String roleName : agents) {
            AgentCatalog.Role role = catalog.require(roleName);
            AgentToolLoop.Actor actor = new AgentToolLoop.Actor(
                    "task-isolation", roleName, "HIERARCHICAL_ACTIVE");
            for (String tool : universe) {
                assertThat(permissions.isAllowed(actor, tool))
                        .as("%s must expose exactly its catalog surface for %s", roleName, tool)
                        .isEqualTo(role.tools().contains(tool));
            }
        }
    }

    @Test
    void unknownRoleAndUnknownToolRemainDeniedInEveryHierarchicalMode() {
        ToolPermissionMatrix permissions = ToolPermissionMatrix.readOnlyAgents();
        for (String mode : Set.of("HIERARCHICAL_SHADOW", "HIERARCHICAL_CANARY", "HIERARCHICAL_ACTIVE")) {
            assertThat(permissions.isAllowed(new AgentToolLoop.Actor("task", "invented-agent", mode),
                    "context.read_file")).isFalse();
            assertThat(permissions.isAllowed(new AgentToolLoop.Actor("task", "developer", mode),
                    "foreign.execute")).isFalse();
        }
    }
}
