package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aSecurityDeclarationTest {

    @Test
    void secureAdvertisementRequiresEffectiveMutualTlsAndOAuth2Configuration() {
        A2aSecurityProperties security = new A2aSecurityProperties(true, true,
                URI.create("https://identity.internal/issuer"),
                URI.create("https://identity.internal/oauth/token"), "ai-factory-a2a");
        AgentRuntimeProperties runtime = new AgentRuntimeProperties(
                "developer", URI.create("https://agent-developer:8090/a2a"));
        MockEnvironment effectiveTls = new MockEnvironment()
                .withProperty("server.ssl.enabled", "true")
                .withProperty("server.ssl.client-auth", "need");

        assertThatCode(() -> A2aSecurityConfiguration.requireSecureTransport(security, runtime, effectiveTls))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> A2aSecurityConfiguration.requireSecureTransport(
                security, runtime, new MockEnvironment().withProperty("server.ssl.enabled", "true")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-auth=need");
        assertThatThrownBy(() -> A2aSecurityConfiguration.requireSecureTransport(
                security, new AgentRuntimeProperties("developer", URI.create("http://agent:8090/a2a")), effectiveTls))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void secureCardDeclaresTheEnforcedSchemesAndCatalogDerivedSkillScope() {
        AgentRuntimeProperties runtime = new AgentRuntimeProperties(
                "developer", URI.create("https://agent-developer:8090/a2a"));
        A2aSecurityProperties security = new A2aSecurityProperties(true, true,
                URI.create("https://identity.internal/issuer"),
                URI.create("https://identity.internal/oauth/token"), "ai-factory-a2a");
        AgentCardController controller = new AgentCardController(runtime, security,
                new AgentCardCatalogGenerator(new tools.jackson.databind.ObjectMapper()));

        Map<String, Object> card = controller.publicCard();
        assertThatCode(() -> ((Map<String, Object>) card.get("securitySchemes")).get("mutualTLS"))
                .doesNotThrowAnyException();
        List<Map<String, Object>> skills = (List<Map<String, Object>>) card.get("skills");
        List<Map<String, List<String>>> requirements =
                (List<Map<String, List<String>>>) skills.getFirst().get("security");
        org.assertj.core.api.Assertions.assertThat(requirements.getFirst().get("oauth2"))
                .containsExactly("a2a.invoke", "a2a.skill.developer.code-task-v1");
    }
}
