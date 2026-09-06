package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aSecurityDeclarationTest {

    @Test
    void secureAdvertisementRequiresEffectiveMutualTlsAndOAuth2Configuration() {
        A2aSecurityProperties security = new A2aSecurityProperties(true, true,
                URI.create("https://identity.internal/issuer"),
                URI.create("https://identity.internal/oauth/token"), "ai-factory-a2a",
                java.time.Duration.ofMinutes(5));
        AgentRuntimeProperties runtime = new AgentRuntimeProperties(
                "developer", URI.create("https://agent-developer:8090/a2a"));
        MockEnvironment effectiveTls = effectiveTls();

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
        assertThatThrownBy(() -> A2aSecurityConfiguration.requireSecureTransport(
                security, runtime, effectiveTls().withProperty("server.ssl.enabled-protocols", "TLSv1.2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TLSv1.3");
    }

    @Test
    @SuppressWarnings("unchecked")
    void secureCardDeclaresTheEnforcedSchemesAndCatalogDerivedSkillScope() {
        AgentRuntimeProperties runtime = new AgentRuntimeProperties(
                "developer", URI.create("https://agent-developer:8090/a2a"));
        A2aSecurityProperties security = new A2aSecurityProperties(true, true,
                URI.create("https://identity.internal/issuer"),
                URI.create("https://identity.internal/oauth/token"), "ai-factory-a2a",
                java.time.Duration.ofMinutes(5));
        A2aCardIdentityProperties identity = new A2aCardIdentityProperties(
                "ai-factory", "AI Software Factory", URI.create("https://ai-factory.local"),
                java.time.Duration.ofMinutes(15));
        AgentCardController controller = new AgentCardController(runtime, security, identity,
                new AgentCardCatalogGenerator(new tools.jackson.databind.ObjectMapper()), testSigner(),
                new A2aPushNotificationProperties(false, null, null, 4, java.time.Duration.ofSeconds(1)));

        Map<String, Object> card = controller.publicCard();
        assertThatCode(() -> ((Map<String, Object>) card.get("securitySchemes")).get("mutualTLS"))
                .doesNotThrowAnyException();
        List<Map<String, Object>> skills = (List<Map<String, Object>>) card.get("skills");
        List<Map<String, List<String>>> requirements =
                (List<Map<String, List<String>>>) skills.getFirst().get("security");
        org.assertj.core.api.Assertions.assertThat(requirements.getFirst().get("oauth2"))
                .containsExactly("a2a.invoke", "a2a.role.developer", "a2a.skill.developer.code-task-v1");
    }

    @Test
    void jwtValidationRejectsWrongAudienceAndExcessiveLifetime() {
        A2aSecurityProperties security = new A2aSecurityProperties(true, true,
                URI.create("https://identity.internal/issuer"),
                URI.create("https://identity.internal/oauth/token"), "ai-factory-a2a",
                java.time.Duration.ofMinutes(5));
        Instant now = Instant.now();
        org.springframework.security.oauth2.jwt.Jwt valid = token(now, now.plusSeconds(240), "ai-factory-a2a");
        org.springframework.security.oauth2.jwt.Jwt wrongAudience = token(now, now.plusSeconds(240), "other");
        org.springframework.security.oauth2.jwt.Jwt excessive = token(now, now.plusSeconds(301), "ai-factory-a2a");

        assertThat(A2aSecurityConfiguration.a2aJwtValidator(security).validate(valid).hasErrors()).isFalse();
        assertThat(A2aSecurityConfiguration.a2aJwtValidator(security).validate(wrongAudience).hasErrors()).isTrue();
        assertThat(A2aSecurityConfiguration.a2aJwtValidator(security).validate(excessive).hasErrors()).isTrue();
    }

    private static A2aAgentCardSigner testSigner() {
        try {
            java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            java.security.KeyPair pair = generator.generateKeyPair();
            return new A2aAgentCardSigner(new tools.jackson.databind.ObjectMapper(), List.of(
                    new com.nimbusds.jose.jwk.RSAKey.Builder(
                            (java.security.interfaces.RSAPublicKey) pair.getPublic())
                            .privateKey(pair.getPrivate()).keyID("test-kid").build()));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static MockEnvironment effectiveTls() {
        return new MockEnvironment()
                .withProperty("server.ssl.enabled", "true")
                .withProperty("server.ssl.client-auth", "need")
                .withProperty("server.ssl.enabled-protocols", "TLSv1.3")
                .withProperty("server.ssl.certificate", "/run/a2a/tls.crt")
                .withProperty("server.ssl.certificate-private-key", "/run/a2a/tls.key")
                .withProperty("server.ssl.trust-certificate", "/run/a2a/ca.crt")
                .withProperty("ai-factory.agent-runtime.security.crl", "/run/a2a/ca.crl");
    }

    private static org.springframework.security.oauth2.jwt.Jwt token(
            Instant issuedAt, Instant expiresAt, String audience) {
        return org.springframework.security.oauth2.jwt.Jwt.withTokenValue("header.payload.signature")
                .header("alg", "RS256")
                .issuer("https://identity.internal/issuer")
                .subject("ai-factory-orchestrator")
                .audience(List.of(audience))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("scope", "a2a.invoke a2a.role.developer a2a.skill.developer.code-task-v1")
                .build();
    }
}
