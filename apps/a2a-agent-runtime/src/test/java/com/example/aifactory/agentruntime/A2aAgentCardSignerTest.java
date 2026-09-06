package com.example.aifactory.agentruntime;

import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class A2aAgentCardSignerTest {

    @Test
    void signsCanonicalCardWithOverlappingKidsAndDetectsTampering() throws Exception {
        A2aAgentCardSigner signer = new A2aAgentCardSigner(new ObjectMapper(),
                List.of(key("active"), key("previous")));
        Map<String, Object> card = new LinkedHashMap<>(Map.of(
                "name", "AI Factory developer",
                "protocolVersion", "1.0",
                "capabilities", Map.of("streaming", false)));
        card.put("signatures", signer.sign(card));

        assertThat(signer.verify(card)).isTrue();
        assertThat((List<?>) card.get("signatures")).hasSize(2);

        card.put("name", "tampered");
        assertThat(signer.verify(card)).isFalse();
    }

    private static RSAKey key(String kid) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey(pair.getPrivate()).keyID(kid).build();
    }
}
