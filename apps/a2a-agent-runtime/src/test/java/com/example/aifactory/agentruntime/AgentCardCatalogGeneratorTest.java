package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCardCatalogGeneratorTest {

    @Test
    void generatesEveryCardFromTheAuthoritativeCatalogWithoutRoleDuplication() {
        AgentCatalog catalog = new AgentCatalog();
        Map<String, AgentCardCatalogGenerator.GeneratedAgentCard> cards =
                new AgentCardCatalogGenerator(catalog, new ObjectMapper()).generate();

        assertThat(cards).hasSize(14).containsOnlyKeys(
                catalog.agentRoles().stream().map(AgentCatalog.Role::name).toList().toArray(String[]::new));
        cards.forEach((roleName, card) -> {
            AgentCatalog.Role role = catalog.require(roleName);
            assertThat(card.catalogId()).isEqualTo(catalog.catalogId());
            assertThat(card.owner()).isEqualTo(role.owner());
            assertThat(card.outputContract()).isEqualTo(role.outputContract());
            assertThat(card.tools()).containsExactlyElementsOf(role.tools());
            assertThat(card.skills()).isNotEmpty().allSatisfy(skill -> {
                assertThat(skill.id()).startsWith(roleName + ".");
                assertThat(skill.acceptedMediaTypes()).isNotEmpty();
            });
        });
    }
}
