package com.example.aifactory.agentruntime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Authenticated fixed-destination delivery with bounded retries and secret-free acknowledgements. */
@Component
public final class A2aPushNotificationSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2aPushNotificationSender.class);

    private final A2aPushNotificationProperties properties;
    private final ObjectMapper mapper;
    private final NotificationTransport transport;
    private final byte[] secret;

    @Autowired
    public A2aPushNotificationSender(A2aPushNotificationProperties properties, ObjectMapper mapper,
                                     WebClient.Builder webClient) {
        this(properties, mapper, httpTransport(webClient), loadSecret(properties));
    }

    A2aPushNotificationSender(A2aPushNotificationProperties properties, ObjectMapper mapper,
                              NotificationTransport transport, byte[] secret) {
        this.properties = properties;
        this.mapper = mapper;
        this.transport = transport;
        this.secret = secret.clone();
        validate(properties, this.secret);
    }

    public boolean enabled() { return properties.enabled(); }

    public CompletionStage<Acknowledgement> send(Notification notification) {
        if (!properties.enabled()) {
            return CompletableFuture.failedFuture(new NotificationDeliveryException("Push notifications are disabled"));
        }
        try {
            byte[] body = mapper.writeValueAsBytes(Map.of(
                    "agentRole", notification.agentRole(),
                    "taskId", notification.taskId(),
                    "contextId", notification.contextId(),
                    "sequence", notification.sequence(),
                    "state", "TASK_STATE_" + notification.state().name(),
                    "occurredAt", notification.occurredAt().toString(),
                    "artifacts", notification.artifacts()));
            return attempt(notification.taskId(), body, signature(body), 1);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(new NotificationDeliveryException(
                    "Cannot serialize push notification", exception));
        }
    }

    private CompletionStage<Acknowledgement> attempt(String taskId, byte[] body, String signature, int attempt) {
        return transport.post(properties.callback(), body, signature).handle((status, failure) -> {
            if (failure == null && status >= 200 && status < 300) {
                LOGGER.info("A2A push acknowledged taskId={} attempt={} status={}", taskId, attempt, status);
                return CompletableFuture.completedFuture(new Acknowledgement(attempt, status));
            }
            if (attempt >= properties.maxAttempts()) {
                LOGGER.warn("A2A push exhausted taskId={} attempts={}", taskId, attempt);
                return CompletableFuture.<Acknowledgement>failedFuture(new NotificationDeliveryException(
                        "Push notification delivery exhausted", failure));
            }
            Duration delay = properties.initialBackoff().multipliedBy(1L << Math.min(attempt - 1, 10));
            CompletableFuture<Acknowledgement> delayed = new CompletableFuture<>();
            CompletableFuture.delayedExecutor(delay.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .execute(() -> attempt(taskId, body, signature, attempt + 1)
                            .whenComplete((ack, retryFailure) -> {
                                if (retryFailure == null) delayed.complete(ack);
                                else delayed.completeExceptionally(retryFailure);
                            }));
            return delayed;
        }).thenCompose(stage -> stage);
    }

    private String signature(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }

    private static NotificationTransport httpTransport(WebClient.Builder builder) {
        WebClient client = builder.build();
        return (callback, body, signature) -> client.post().uri(callback)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-A2A-Notification-Signature", signature)
                .header(HttpHeaders.USER_AGENT, "ai-factory-a2a-agent/1.0")
                .bodyValue(body)
                .exchangeToMono(response -> reactor.core.publisher.Mono.just(response.statusCode().value()))
                .toFuture();
    }

    private static byte[] loadSecret(A2aPushNotificationProperties properties) {
        if (!properties.enabled()) return "disabled-not-used-secret-material".getBytes(StandardCharsets.UTF_8);
        try {
            if (properties.hmacSecretFile() == null || properties.hmacSecretFile().isBlank()) {
                throw new NotificationDeliveryException("Push HMAC secret file is required");
            }
            return Files.readAllBytes(Path.of(properties.hmacSecretFile()));
        } catch (NotificationDeliveryException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new NotificationDeliveryException("Cannot read push HMAC secret file", exception);
        }
    }

    private static void validate(A2aPushNotificationProperties properties, byte[] secret) {
        Objects.requireNonNull(properties, "properties");
        if (!properties.enabled()) return;
        if (properties.callback() == null || !"https".equalsIgnoreCase(properties.callback().getScheme())
                || properties.callback().getHost() == null || properties.callback().getUserInfo() != null
                || properties.callback().getFragment() != null) {
            throw new NotificationDeliveryException("Push callback must be one fixed HTTPS URL");
        }
        if (properties.maxAttempts() < 1 || properties.maxAttempts() > 8
                || properties.initialBackoff() == null || properties.initialBackoff().isNegative()
                || properties.initialBackoff().compareTo(Duration.ofMinutes(1)) > 0) {
            throw new NotificationDeliveryException("Push retry policy is invalid");
        }
        if (secret.length < 32) throw new NotificationDeliveryException("Push HMAC secret must contain at least 32 bytes");
    }

    public interface NotificationTransport {
        CompletionStage<Integer> post(URI callback, byte[] body, String signature);
    }

    public record Notification(
            String agentRole, String taskId, String contextId, long sequence, A2aSendMessageService.TaskState state,
            Instant occurredAt, List<Map<String, Object>> artifacts) {
        public Notification { artifacts = List.copyOf(artifacts); }
    }

    public record Acknowledgement(int attempts, int httpStatus) {}

    public static final class NotificationDeliveryException extends RuntimeException {
        public NotificationDeliveryException(String message) { super(message); }
        public NotificationDeliveryException(String message, Throwable cause) { super(message, cause); }
    }
}
