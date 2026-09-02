package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentCatalogTest {
    private final AgentCatalog catalog = new AgentCatalog();

    @Test
    void loadsTheVersionedHierarchyAndCapabilities() {
        assertThat(catalog.catalogId()).isEqualTo("multi-agent-hierarchy-v1");
        assertThat(catalog.roles()).hasSize(15);
        assertThat(catalog.require("supervisor").mayDelegateTo())
                .containsExactly("architecture-agent", "code-agent", "test-agent", "security-agent");
        assertThat(catalog.require("independent-reviewer").parent()).isEqualTo("workflow");
        assertThat(catalog.require("developer").tools()).doesNotContain("sandbox.apply_patch");
    }

    @Test
    void failsClosedForUnknownRole() {
        assertThatThrownBy(() -> catalog.require("admin-agent"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown agent role");
    }
}
