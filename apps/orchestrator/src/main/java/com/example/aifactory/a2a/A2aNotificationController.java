package com.example.aifactory.a2a;

import com.example.aifactory.config.A2aNotificationProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@RestController
public final class A2aNotificationController {
    public static final String PATH = "/internal/a2a/notifications";
    private final A2aNotificationProperties properties;
    private final A2aNotificationReceiver receiver;
    private final ObjectMapper mapper;
    private final SecretProvider secretProvider;

    @Autowired
    public A2aNotificationController(A2aNotificationProperties properties,
                                     A2aNotificationReceiver receiver, ObjectMapper mapper) {
        this(properties, receiver, mapper, () -> loadSecret(properties));
    }

    A2aNotificationController(A2aNotificationProperties properties, A2aNotificationReceiver receiver,
                              ObjectMapper mapper, byte[] secret) {
        this(properties, receiver, mapper, () -> secret.clone());
    }

    private A2aNotificationController(A2aNotificationProperties properties, A2aNotificationReceiver receiver,
                                      ObjectMapper mapper, SecretProvider secretProvider) {
        this.properties = properties;
        this.receiver = receiver;
        this.mapper = mapper;
        this.secretProvider = secretProvider;
        if (properties.enabled()) {
            byte[] probe = secretProvider.acquire();
            try {
                requireSecret(probe);
            } finally {
                java.util.Arrays.fill(probe, (byte) 0);
            }
        }
    }

    @PostMapping(path = PATH, consumes = "application/json")
    Mono<ResponseEntity<Void>> receive(
            @RequestHeader(name = "X-A2A-Notification-Signature", required = false) String signature,
            @RequestBody byte[] body) {
        return Mono.fromCallable(() -> authenticateAndParse(signature, body))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .flatMap(notification -> Mono.fromCompletionStage(receiver.receive(notification)))
                .thenReturn(ResponseEntity.accepted().build());
    }

    private A2aContracts.Notification authenticateAndParse(String signature, byte[] body) throws Exception {
        if (!properties.enabled() || body == null || body.length == 0 || body.length > properties.maxBodyBytes()) {
            throw new SecurityException("A2A notification endpoint is unavailable");
        }
        byte[] expected = hmac(body);
        byte[] supplied;
        try {
            if (signature == null || !signature.startsWith("sha256=")) throw new IllegalArgumentException();
            supplied = HexFormat.of().parseHex(signature.substring("sha256=".length()));
        } catch (RuntimeException invalid) {
            throw new SecurityException("Invalid A2A notification authentication");
        }
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new SecurityException("Invalid A2A notification authentication");
        }
        JsonNode value = mapper.readTree(body);
        List<A2aContracts.Artifact> artifacts = new ArrayList<>();
        for (JsonNode rawArtifact : value.path("artifacts")) {
            List<A2aContracts.Part> parts = new ArrayList<>();
            for (JsonNode rawPart : rawArtifact.path("parts")) {
                @SuppressWarnings("unchecked") Map<String, Object> data = mapper.convertValue(
                        rawPart.path("data"), Map.class);
                URI uri = data.get("uri") instanceof String text ? URI.create(text) : null;
                parts.add(new A2aContracts.Part(A2aMediaTypes.EVIDENCE_REFERENCE, null, data, uri));
            }
            artifacts.add(new A2aContracts.Artifact(rawArtifact.path("artifactId").asText(),
                    rawArtifact.path("name").asText(), parts, Map.of()));
        }
        String state = value.path("state").asText().replace("TASK_STATE_", "");
        return new A2aContracts.Notification(value.path("agentRole").asText(), value.path("taskId").asText(),
                value.path("contextId").asText(), value.path("sequence").asLong(-1),
                A2aContracts.TaskState.valueOf(state), Instant.parse(value.path("occurredAt").asText()),
                artifacts, Map.of());
    }

    private byte[] hmac(byte[] body) throws Exception {
        byte[] secret = secretProvider.acquire();
        try {
            requireSecret(secret);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body);
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
    }

    private static byte[] loadSecret(A2aNotificationProperties properties) {
        if (!properties.enabled()) return "disabled-not-used-secret-material".getBytes(StandardCharsets.UTF_8);
        try {
            return A2aSecretFilePolicy.read(Path.of(properties.hmacSecretFile()), 65_536);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot load A2A notification HMAC secret", failure);
        }
    }

    private static void requireSecret(byte[] secret) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("A2A notification HMAC secret must contain at least 32 bytes");
        }
    }

    @FunctionalInterface
    private interface SecretProvider { byte[] acquire(); }
}
