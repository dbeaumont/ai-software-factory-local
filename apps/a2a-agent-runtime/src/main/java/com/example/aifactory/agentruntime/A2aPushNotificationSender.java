package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.SecureUriPolicy;
import com.example.aifactory.agentcore.SecretFilePolicy;
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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Authenticated fixed-destination delivery with bounded retries and secret-free acknowledgements. */
@Component
public final class A2aPushNotificationSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2aPushNotificationSender.class);

    private final A2aPushNotificationProperties properties;
    private final ObjectMapper mapper;
    private final NotificationTransport transport;
    private final SecretProvider secretProvider;
    private final SecureUriPolicy urlPolicy;
    private final A2aIdentityRateLimiter rateLimiter;
    private final A2aServerMetrics metrics;

    @Autowired
    public A2aPushNotificationSender(A2aPushNotificationProperties properties, ObjectMapper mapper,
                                     WebClient.Builder webClient, A2aIdentityRateLimiter rateLimiter,
                                     A2aServerMetrics metrics) {
        this(properties, mapper, httpTransport(webClient), () -> loadSecret(properties), systemPolicy(properties),
                rateLimiter, metrics);
    }

    A2aPushNotificationSender(A2aPushNotificationProperties properties, ObjectMapper mapper,
                              NotificationTransport transport, byte[] secret) {
        this(properties, mapper, transport, constantSecret(secret), testPolicy(properties),
                new A2aIdentityRateLimiter(A2aRateLimitProperties.defaults()), A2aServerMetrics.disabled());
    }

    A2aPushNotificationSender(A2aPushNotificationProperties properties, ObjectMapper mapper,
                              NotificationTransport transport, byte[] secret, SecureUriPolicy urlPolicy) {
        this(properties, mapper, transport, constantSecret(secret), urlPolicy,
                new A2aIdentityRateLimiter(A2aRateLimitProperties.defaults()), A2aServerMetrics.disabled());
    }

    A2aPushNotificationSender(A2aPushNotificationProperties properties, ObjectMapper mapper,
                              NotificationTransport transport, byte[] secret, SecureUriPolicy urlPolicy,
                              A2aIdentityRateLimiter rateLimiter) {
        this(properties, mapper, transport, constantSecret(secret), urlPolicy, rateLimiter,
                A2aServerMetrics.disabled());
    }

    private A2aPushNotificationSender(A2aPushNotificationProperties properties, ObjectMapper mapper,
                              NotificationTransport transport, SecretProvider secretProvider,
                              SecureUriPolicy urlPolicy, A2aIdentityRateLimiter rateLimiter,
                              A2aServerMetrics metrics) {
        this.properties = properties;
        this.mapper = mapper;
        this.transport = transport;
        this.secretProvider = secretProvider;
        this.urlPolicy = urlPolicy;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        byte[] probe = secretProvider.acquire();
        try {
            validate(properties, probe);
        } finally {
            java.util.Arrays.fill(probe, (byte) 0);
        }
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
            return attempt(notification.agentRole(), notification.taskId(), body, signature(body), 1);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(new NotificationDeliveryException(
                    "Cannot serialize push notification", exception));
        }
    }

    private CompletionStage<Acknowledgement> attempt(String identity, String taskId, byte[] body, String signature,
                                                     int attempt) {
        try {
            rateLimiter.acquire(identity, A2aIdentityRateLimiter.Operation.NOTIFICATION);
            urlPolicy.requireAllowed(properties.callback());
        } catch (A2aOperationalException limited) {
            return CompletableFuture.failedFuture(limited);
        } catch (SecurityException blocked) {
            return CompletableFuture.failedFuture(new NotificationDeliveryException(
                    "Push callback network target is forbidden", blocked));
        }
        return transport.post(properties.callback(), body, signature).handle((status, failure) -> {
            if (failure == null && status >= 200 && status < 300) {
                metrics.notification("delivered", notificationState(body));
                LOGGER.info("A2A push acknowledged taskId={} attempt={} status={}", taskId, attempt, status);
                return CompletableFuture.completedFuture(new Acknowledgement(attempt, status));
            }
            if (attempt >= properties.maxAttempts()) {
                metrics.notification("failed", notificationState(body));
                LOGGER.warn("A2A push exhausted taskId={} attempts={}", taskId, attempt);
                return CompletableFuture.<Acknowledgement>failedFuture(new NotificationDeliveryException(
                        "Push notification delivery exhausted", failure));
            }
            metrics.notification("retries", notificationState(body));
            Duration delay = properties.initialBackoff().multipliedBy(1L << Math.min(attempt - 1, 10));
            CompletableFuture<Acknowledgement> delayed = new CompletableFuture<>();
            CompletableFuture.delayedExecutor(delay.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    .execute(() -> attempt(identity, taskId, body, signature, attempt + 1)
                            .whenComplete((ack, retryFailure) -> {
                                if (retryFailure == null) delayed.complete(ack);
                                else delayed.completeExceptionally(retryFailure);
                            }));
            return delayed;
        }).thenCompose(stage -> stage);
    }

    private A2aSendMessageService.TaskState notificationState(byte[] body) {
        try {
            String value = mapper.readTree(body).path("state").asText();
            return A2aSendMessageService.TaskState.valueOf(value.replace("TASK_STATE_", ""));
        } catch (Exception invalid) {
            return A2aSendMessageService.TaskState.FAILED;
        }
    }

    private String signature(byte[] body) throws Exception {
        byte[] secret = secretProvider.acquire();
        try {
            validate(properties, secret);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
        } finally {
            java.util.Arrays.fill(secret, (byte) 0);
        }
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
            return SecretFilePolicy.read(Path.of(properties.hmacSecretFile()), 65_536);
        } catch (NotificationDeliveryException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new NotificationDeliveryException("Cannot read push HMAC secret file", exception);
        }
    }

    private static SecretProvider constantSecret(byte[] secret) {
        byte[] retained = secret.clone();
        return () -> retained.clone();
    }

    private static SecureUriPolicy systemPolicy(A2aPushNotificationProperties properties) {
        if (!properties.enabled()) return null;
        try {
            return SecureUriPolicy.system(Set.of(properties.callback()));
        } catch (RuntimeException failure) {
            throw new NotificationDeliveryException("Push callback must be one fixed HTTPS URL", failure);
        }
    }

    private static SecureUriPolicy testPolicy(A2aPushNotificationProperties properties) {
        if (!properties.enabled()) return null;
        try {
            return new SecureUriPolicy(Set.of(properties.callback()), host ->
                    List.of(java.net.InetAddress.getByName("192.0.2.10")));
        } catch (RuntimeException failure) {
            throw new NotificationDeliveryException("Push callback must be one fixed HTTPS URL", failure);
        }
    }

    private static void validate(A2aPushNotificationProperties properties, byte[] secret) {
        Objects.requireNonNull(properties, "properties");
        if (!properties.enabled()) return;
        if (properties.callback() == null || !"https".equalsIgnoreCase(properties.callback().getScheme())
                || properties.callback().getHost() == null || properties.callback().getUserInfo() != null
                || properties.callback().getQuery() != null || properties.callback().getFragment() != null) {
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

    @FunctionalInterface
    private interface SecretProvider { byte[] acquire(); }

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
