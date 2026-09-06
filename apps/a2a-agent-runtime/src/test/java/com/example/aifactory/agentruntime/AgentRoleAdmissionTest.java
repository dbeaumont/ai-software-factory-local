package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.AgentCatalog;
import com.example.aifactory.agentcore.AgentManifest;
import com.example.aifactory.agentcore.PromptRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentRoleAdmissionTest {
    private final AgentCatalog catalog = new AgentCatalog();
    private final PromptRepository prompts = new PromptRepository();

    @Test
    void everyCatalogAgentHasOneCompatibleEmbeddedManifest() {
        assertEquals(14, catalog.agentRoles().stream()
                .map(role -> AgentManifest.load(role.name(), catalog, prompts)).count());
    }

    @Test
    void rejectsMissingUnknownAndControlPlaneRoles() {
        assertThrows(IllegalStateException.class, () -> AgentManifest.load(null, catalog, prompts));
        assertThrows(IllegalStateException.class, () -> AgentManifest.load("unknown-agent", catalog, prompts));
        assertThrows(IllegalStateException.class, () -> AgentManifest.load("workflow", catalog, prompts));
    }

    @Test
    void applicationStartupFailsWhenRoleIsMissing() {
        assertThrows(Exception.class, () -> new SpringApplicationBuilder(A2aAgentRuntimeApplication.class)
                .profiles("agent-runtime")
                .properties(Map.of("spring.main.web-application-type", "none",
                        "spring.main.banner-mode", "off"))
                .run().close());
    }
}
