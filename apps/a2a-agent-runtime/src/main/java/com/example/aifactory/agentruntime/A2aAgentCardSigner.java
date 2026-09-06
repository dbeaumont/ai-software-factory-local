package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.SecretFilePolicy;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Produces detached JWS signatures over an RFC 8785 canonical Agent Card. */
@Component
public final class A2aAgentCardSigner {
    private final ObjectMapper mapper;
    private final List<RSAKey> keys;

    @Autowired
    public A2aAgentCardSigner(ObjectMapper mapper, A2aCardSigningProperties properties,
                             A2aSecurityProperties security) {
        this.mapper = mapper;
        this.keys = loadKeys(properties, security);
    }

    A2aAgentCardSigner(ObjectMapper mapper, List<RSAKey> keys) {
        this.mapper = mapper;
        this.keys = List.copyOf(keys);
    }

    public List<Map<String, Object>> sign(Map<String, Object> unsignedCard) {
        byte[] canonical = canonicalize(unsignedCard);
        return keys.stream().map(key -> sign(canonical, key)).toList();
    }

    public boolean verify(Map<String, Object> signedCard) {
        Object rawSignatures = signedCard.get("signatures");
        if (!(rawSignatures instanceof List<?> signatures) || signatures.isEmpty()) {
            return false;
        }
        Map<String, Object> unsigned = new LinkedHashMap<>(signedCard);
        unsigned.remove("signatures");
        String encodedPayload = com.nimbusds.jose.util.Base64URL.encode(canonicalize(unsigned)).toString();
        return signatures.stream().allMatch(raw -> verifySignature(raw, encodedPayload));
    }

    private Map<String, Object> sign(byte[] canonical, RSAKey key) {
        try {
            JWSObject jws = new JWSObject(new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(key.getKeyID()).build(), new Payload(canonical));
            jws.sign(new RSASSASigner(key));
            Map<String, Object> unprotected = Map.of("jwk", key.toPublicJWK().toJSONObject());
            return Map.of(
                    "header", unprotected,
                    "protected", jws.getHeader().toBase64URL().toString(),
                    "signature", jws.getSignature().toString());
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot sign Agent Card with kid " + key.getKeyID(), exception);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean verifySignature(Object raw, String encodedPayload) {
        try {
            Map<String, Object> signature = (Map<String, Object>) raw;
            Map<String, Object> header = (Map<String, Object>) signature.get("header");
            RSAKey publicKey = RSAKey.parse((Map<String, Object>) header.get("jwk"));
            JWSObject jws = JWSObject.parse(signature.get("protected") + "." + encodedPayload + "."
                    + signature.get("signature"));
            return publicKey.getKeyID().equals(jws.getHeader().getKeyID())
                    && JWSAlgorithm.RS256.equals(jws.getHeader().getAlgorithm())
                    && jws.verify(new RSASSAVerifier(publicKey));
        } catch (Exception exception) {
            return false;
        }
    }

    private byte[] canonicalize(Map<String, Object> card) {
        try {
            byte[] json = mapper.writeValueAsBytes(card);
            return new JsonCanonicalizer(json).getEncodedUTF8();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot canonicalize Agent Card", exception);
        }
    }

    private static List<RSAKey> loadKeys(A2aCardSigningProperties properties, A2aSecurityProperties security) {
        String path = properties.jwkSetPath();
        if (path == null || path.isBlank()) {
            if (security.enabled()) {
                throw new IllegalStateException("Secured runtime requires a mounted Agent Card signing JWK Set");
            }
            return List.of(generateEphemeralDevelopmentKey());
        }
        try {
            byte[] encoded = SecretFilePolicy.read(Path.of(path), 1_048_576);
            JWKSet set;
            try {
                set = JWKSet.parse(new String(encoded, StandardCharsets.UTF_8));
            } finally {
                java.util.Arrays.fill(encoded, (byte) 0);
            }
            List<RSAKey> loaded = new ArrayList<>();
            for (JWK key : set.getKeys()) {
                if (!(key instanceof RSAKey rsa) || rsa.isPrivate() == false || rsa.getKeyID() == null) {
                    throw new IllegalStateException("Every card key must be a private RSA JWK with kid");
                }
                if (security.enabled() && (rsa.getX509CertChain() == null || rsa.getX509CertChain().isEmpty())) {
                    throw new IllegalStateException("Secured card keys must publish an x5c trust chain");
                }
                loaded.add(rsa);
            }
            if (loaded.isEmpty()) {
                throw new IllegalStateException("Agent Card JWK Set is empty");
            }
            String active = properties.activeKeyId();
            if (active == null || loaded.stream().noneMatch(key -> active.equals(key.getKeyID()))) {
                throw new IllegalStateException("Agent Card active kid is absent from the JWK Set");
            }
            loaded.sort(Comparator.comparing(key -> active.equals(key.getKeyID()) ? 0 : 1));
            return List.copyOf(loaded);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load Agent Card signing JWK Set", exception);
        }
    }

    private static RSAKey generateEphemeralDevelopmentKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            String kid = "dev-ephemeral-" + UUID.randomUUID();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey(pair.getPrivate()).keyID(kid).build();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot generate development card signing key", exception);
        }
    }
}
