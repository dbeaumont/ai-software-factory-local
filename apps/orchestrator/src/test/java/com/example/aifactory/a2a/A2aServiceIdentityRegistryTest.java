package com.example.aifactory.a2a;

import com.example.aifactory.service.AgentCatalog;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aServiceIdentityRegistryTest {

    @Test
    void assignsAUniqueIdentityAndCredentialReferenceToEveryWorkload() {
        A2aServiceIdentityRegistry registry = registry();

        assertThat(registry.audience()).isEqualTo("ai-factory-a2a");
        assertThat(registry.agents()).hasSize(14);
        assertThat(registry.agents().values()).extracting(A2aServiceIdentityRegistry.Identity::clientId)
                .doesNotHaveDuplicates().doesNotContain(registry.orchestrator().clientId());
        assertThat(registry.agents().values()).extracting(A2aServiceIdentityRegistry.Identity::subject)
                .doesNotHaveDuplicates().doesNotContain(registry.orchestrator().subject());
        assertThat(registry.agents().values()).extracting(A2aServiceIdentityRegistry.Identity::credentialRef)
                .doesNotHaveDuplicates().doesNotContain(registry.orchestrator().credentialRef());
    }

    @Test
    void preventsOneAgentFromPresentingAnotherRolesCredentials() {
        A2aServiceIdentityRegistry registry = registry();
        A2aServiceIdentityRegistry.Identity developer = registry.requireAgent("developer");

        assertThat(registry.authorizeAgent("developer", developer.clientId(), developer.subject()))
                .isEqualTo(developer);
        assertThatThrownBy(() -> registry.authorizeAgent("developer",
                registry.requireAgent("test-design").clientId(), developer.subject()))
                .isInstanceOf(A2aServiceIdentityRegistry.IdentityViolation.class)
                .hasMessageContaining("not authorized");
        assertThatThrownBy(() -> registry.authorizeAgent("developer", developer.clientId(),
                URI.create("spiffe://ai-factory.local/agent/test-design")))
                .isInstanceOf(A2aServiceIdentityRegistry.IdentityViolation.class)
                .hasMessageContaining("not authorized");
    }

    @Test
    void rejectsARegistryThatSharesCredentialsBetweenRoles() throws Exception {
        String source;
        try (var input = getClass().getClassLoader().getResourceAsStream("a2a/service-identities-v1.json")) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String invalid = source.replace(
                "secret://a2a/agent/test-design/oauth2-client",
                "secret://a2a/agent/developer/oauth2-client");

        assertThatThrownBy(() -> new A2aServiceIdentityRegistry(new ObjectMapper(), new AgentCatalog(),
                new ByteArrayInputStream(invalid.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(A2aServiceIdentityRegistry.IdentityViolation.class)
                .hasMessageContaining("must not be shared");
    }

    private static A2aServiceIdentityRegistry registry() {
        return new A2aServiceIdentityRegistry(new ObjectMapper(), new AgentCatalog());
    }
}
