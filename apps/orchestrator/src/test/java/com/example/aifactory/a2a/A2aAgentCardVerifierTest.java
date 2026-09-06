package com.example.aifactory.a2a;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aAgentCardVerifierTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private RSAKey key;
    private A2aAgentCardVerifier verifier;
    private A2aAgentCardVerifier.VerificationPolicy policy;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey(pair.getPrivate()).keyID("card-2026-09").build();
        String fingerprint = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(key.toRSAPublicKey().getEncoded()));
        verifier = new A2aAgentCardVerifier(mapper);
        policy = new A2aAgentCardVerifier.VerificationPolicy(
                "developer",
                URI.create("https://agent-developer/.well-known/agent-card.json"),
                URI.create("https://agent-developer:8090/a2a"),
                URI.create("https://ai-factory.local"),
                "ai-factory",
                Set.of("developer.code-task-v1"),
                Map.of(key.getKeyID(), fingerprint),
                Instant.parse("2026-09-06T12:00:00Z"));
    }

    @Test
    void admitsOnlyAValidTrustedAndCatalogCompatibleCard() throws Exception {
        byte[] json = signedCard("developer", "2026-09-06T12:15:00Z");
        A2aContracts.AgentCardDescriptor descriptor = verifier.verify(json, policy);

        assertThat(descriptor.agentRole()).isEqualTo("developer");
        assertThat(descriptor.protocolBinding()).isEqualTo("JSONRPC");
        assertThat(descriptor.skillIds()).containsExactly("developer.code-task-v1");
        assertThat(descriptor.cardDigest()).hasSize(64);
    }

    @Test
    void rejectsTamperingExpiryAndWrongRoleBeforeInvocation() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> tampered = mapper.readValue(signedCard("developer", "2026-09-06T12:15:00Z"), Map.class);
        tampered.put("url", "https://attacker.invalid/a2a");
        assertThatThrownBy(() -> verifier.verify(mapper.writeValueAsBytes(tampered), policy))
                .isInstanceOf(A2aAgentCardVerifier.CardVerificationException.class)
                .hasMessageContaining("signature");
        assertThatThrownBy(() -> verifier.verify(signedCard("developer", "2026-09-06T11:59:59Z"), policy))
                .isInstanceOf(A2aAgentCardVerifier.CardVerificationException.class)
                .hasMessageContaining("expired");
        assertThatThrownBy(() -> verifier.verify(signedCard("patch-repair", "2026-09-06T12:15:00Z"), policy))
                .isInstanceOf(A2aAgentCardVerifier.CardVerificationException.class)
                .hasMessageContaining("role");
    }

    private byte[] signedCard(String role, String expiresAt) throws Exception {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("protocolVersion", "1.0");
        card.put("name", "AI Factory " + role);
        card.put("provider", Map.of("organization", "AI Software Factory", "url", "https://ai-factory.local"));
        card.put("url", "https://agent-developer:8090/a2a");
        card.put("preferredTransport", "JSONRPC");
        card.put("supportedInterfaces", List.of(Map.of(
                "url", "https://agent-developer:8090/a2a",
                "protocolBinding", "JSONRPC", "protocolVersion", "1.0")));
        card.put("capabilities", Map.of("streaming", false, "pushNotifications", false));
        card.put("metadata", Map.of("issuer", "ai-factory", "role", role, "expiresAt", expiresAt));
        card.put("skills", List.of(Map.of("id", "developer.code-task-v1")));
        byte[] canonical = new JsonCanonicalizer(mapper.writeValueAsBytes(card)).getEncodedUTF8();
        JWSObject jws = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new Payload(canonical));
        jws.sign(new RSASSASigner(key));
        card.put("signatures", List.of(Map.of(
                "header", Map.of("jwk", key.toPublicJWK().toJSONObject()),
                "protected", jws.getHeader().toBase64URL().toString(),
                "signature", jws.getSignature().toString())));
        return mapper.writeValueAsBytes(card);
    }
}
