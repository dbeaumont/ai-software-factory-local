package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aPushNotificationSenderTest {

    @Test
    void signsForTheFixedCallbackAndRetriesWithABoundedPolicy() {
        ArrayDeque<Integer> statuses = new ArrayDeque<>(List.of(503, 202));
        AtomicReference<String> signature = new AtomicReference<>();
        AtomicReference<URI> callback = new AtomicReference<>();
        A2aPushNotificationProperties properties = new A2aPushNotificationProperties(
                true, URI.create("https://orchestrator.internal/a2a/notifications"), "/mounted/secret", 3,
                Duration.ZERO);
        A2aPushNotificationSender sender = new A2aPushNotificationSender(properties, new ObjectMapper(),
                (uri, body, value) -> {
                    callback.set(uri);
                    signature.set(value);
                    return CompletableFuture.completedFuture(statuses.removeFirst());
                }, "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        A2aPushNotificationSender.Acknowledgement acknowledgement = sender.send(
                new A2aPushNotificationSender.Notification("developer", "task-1", "context-1", 1,
                        A2aSendMessageService.TaskState.COMPLETED, Instant.now(), List.of()))
                .toCompletableFuture().join();

        assertThat(acknowledgement.attempts()).isEqualTo(2);
        assertThat(acknowledgement.httpStatus()).isEqualTo(202);
        assertThat(callback.get()).isEqualTo(properties.callback());
        assertThat(signature.get()).matches("sha256=[a-f0-9]{64}");
    }

    @Test
    void rejectsDynamicOrInsecureCallbacksAndExhaustsRetries() {
        byte[] secret = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new A2aPushNotificationSender(
                new A2aPushNotificationProperties(true, URI.create("http://attacker.invalid/callback"),
                        "/mounted/secret", 2, Duration.ZERO),
                new ObjectMapper(), (uri, body, signature) -> CompletableFuture.completedFuture(200), secret))
                .isInstanceOf(A2aPushNotificationSender.NotificationDeliveryException.class)
                .hasMessageContaining("HTTPS");

        A2aPushNotificationSender sender = new A2aPushNotificationSender(
                new A2aPushNotificationProperties(true, URI.create("https://orchestrator.internal/callback"),
                        "/mounted/secret", 2, Duration.ZERO),
                new ObjectMapper(), (uri, body, signature) -> CompletableFuture.completedFuture(503), secret);
        assertThatThrownBy(() -> sender.send(new A2aPushNotificationSender.Notification(
                        "developer", "task-1", "context-1", 1, A2aSendMessageService.TaskState.FAILED,
                        Instant.now(), List.of())).toCompletableFuture().join())
                .hasRootCauseInstanceOf(A2aPushNotificationSender.NotificationDeliveryException.class);
    }

    @Test
    void failsClosedWhenCallbackDnsChangesAfterItWasPinned() throws Exception {
        byte[] secret = "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        URI callback = URI.create("https://orchestrator.internal/callback");
        java.util.concurrent.atomic.AtomicReference<String> address =
                new java.util.concurrent.atomic.AtomicReference<>("192.0.2.10");
        com.example.aifactory.agentcore.SecureUriPolicy policy =
                new com.example.aifactory.agentcore.SecureUriPolicy(java.util.Set.of(callback), host ->
                        List.of(java.net.InetAddress.getByName(address.get())));
        A2aPushNotificationSender sender = new A2aPushNotificationSender(
                new A2aPushNotificationProperties(true, callback, "/mounted/secret", 1, Duration.ZERO),
                new ObjectMapper(), (uri, body, signature) -> CompletableFuture.completedFuture(202), secret, policy);

        sender.send(new A2aPushNotificationSender.Notification("developer", "task-1", "context-1", 1,
                A2aSendMessageService.TaskState.WORKING, Instant.now(), List.of())).toCompletableFuture().join();
        address.set("192.0.2.11");
        assertThatThrownBy(() -> sender.send(new A2aPushNotificationSender.Notification(
                        "developer", "task-1", "context-1", 2, A2aSendMessageService.TaskState.COMPLETED,
                        Instant.now(), List.of())).toCompletableFuture().join())
                .hasRootCauseMessage("Outbound URI DNS resolution changed after pinning");
    }
}
