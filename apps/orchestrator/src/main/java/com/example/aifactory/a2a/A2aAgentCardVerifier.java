package com.example.aifactory.a2a;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.Base64URL;
import org.erdtman.jcs.JsonCanonicalizer;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fail-closed verification boundary for signed public Agent Cards. */
public final class A2aAgentCardVerifier {
    private final ObjectMapper mapper;

    public A2aAgentCardVerifier(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public A2aContracts.AgentCardDescriptor verify(byte[] json, VerificationPolicy policy) {
        if (json == null || json.length == 0 || json.length > A2aPayloadLimits.MAX_REQUEST_BYTES) {
            throw new CardVerificationException("Agent Card size is invalid");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> card = mapper.readValue(json, Map.class);
            Map<String, Object> unsigned = new LinkedHashMap<>(card);
            Object signatures = unsigned.remove("signatures");
            byte[] canonical = new JsonCanonicalizer(mapper.writeValueAsBytes(unsigned)).getEncodedUTF8();
            verifySignature(signatures, canonical, policy.trustedKeyFingerprints());

            require("1.0".equals(card.get("protocolVersion")), "Unsupported protocol version");
            require("JSONRPC".equals(card.get("preferredTransport")), "Unsupported protocol binding");
            require(policy.expectedEndpoint().toString().equals(card.get("url")), "Unexpected agent endpoint");

            Map<?, ?> provider = object(card, "provider");
            require(policy.expectedProvider().toString().equals(provider.get("url")), "Unexpected card provider");
            Map<?, ?> metadata = object(card, "metadata");
            require(policy.expectedIssuer().equals(metadata.get("issuer")), "Unexpected card issuer");
            require(policy.expectedRole().equals(metadata.get("role")), "Unexpected agent role");
            Instant expiresAt = Instant.parse(string(metadata, "expiresAt"));
            require(expiresAt.isAfter(policy.now()), "Agent Card is expired");

            Set<String> skills = new HashSet<>();
            for (Object raw : list(card, "skills")) {
                skills.add(string((Map<?, ?>) raw, "id"));
            }
            require(skills.equals(policy.expectedSkills()), "Agent Card skills diverge from catalog");
            String digest = hex(MessageDigest.getInstance("SHA-256").digest(canonical));
            return new A2aContracts.AgentCardDescriptor(
                    policy.expectedRole(), policy.expectedCardUri(), policy.expectedEndpoint(), "JSONRPC", "1.0",
                    digest, skills.stream().sorted().toList(), false, capability(card, "pushNotifications"));
        } catch (CardVerificationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CardVerificationException("Invalid Agent Card", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifySignature(Object rawSignatures, byte[] canonical,
                                        Map<String, String> trustedFingerprints) throws Exception {
        require(rawSignatures instanceof List<?> list && !list.isEmpty(), "Agent Card is unsigned");
        String payload = Base64URL.encode(canonical).toString();
        for (Object raw : (List<?>) rawSignatures) {
            try {
                Map<String, Object> signature = (Map<String, Object>) raw;
                Map<String, Object> header = (Map<String, Object>) signature.get("header");
                RSAKey key = RSAKey.parse((Map<String, Object>) header.get("jwk"));
                JWSObject jws = JWSObject.parse(signature.get("protected") + "." + payload + "."
                        + signature.get("signature"));
                String trusted = trustedFingerprints.get(jws.getHeader().getKeyID());
                String actual = hex(MessageDigest.getInstance("SHA-256").digest(key.toRSAPublicKey().getEncoded()));
                if (trusted != null && trusted.equals(actual) && key.getKeyID().equals(jws.getHeader().getKeyID())
                        && JWSAlgorithm.RS256.equals(jws.getHeader().getAlgorithm())
                        && jws.verify(new RSASSAVerifier(key))) {
                    return;
                }
            } catch (Exception ignored) {
                // Try another trusted signature during the key rotation overlap.
            }
        }
        throw new CardVerificationException("No valid signature from a trusted card key");
    }

    private static boolean capability(Map<String, Object> card, String name) {
        Object value = object(card, "capabilities").get(name);
        require(value instanceof Boolean, "Missing capability " + name);
        return (Boolean) value;
    }

    private static Map<?, ?> object(Map<String, Object> source, String field) {
        Object value = source.get(field);
        require(value instanceof Map<?, ?>, "Missing object " + field);
        return (Map<?, ?>) value;
    }

    private static List<?> list(Map<String, Object> source, String field) {
        Object value = source.get(field);
        require(value instanceof List<?>, "Missing list " + field);
        return (List<?>) value;
    }

    private static String string(Map<?, ?> source, String field) {
        Object value = source.get(field);
        require(value instanceof String text && !text.isBlank(), "Missing text " + field);
        return (String) value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new CardVerificationException(message);
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    public record VerificationPolicy(
            String expectedRole,
            URI expectedCardUri,
            URI expectedEndpoint,
            URI expectedProvider,
            String expectedIssuer,
            Set<String> expectedSkills,
            Map<String, String> trustedKeyFingerprints,
            Instant now) {
        public VerificationPolicy {
            expectedSkills = Set.copyOf(expectedSkills);
            trustedKeyFingerprints = Map.copyOf(trustedKeyFingerprints);
        }
    }

    public static final class CardVerificationException extends RuntimeException {
        public CardVerificationException(String message) { super(message); }
        public CardVerificationException(String message, Throwable cause) { super(message, cause); }
    }
}
