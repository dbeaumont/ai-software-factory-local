package com.example.aifactory.a2a;

import com.example.aifactory.config.A2aNotificationProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aNotificationControllerTest {

    @Test
    void authenticatesTheRawBodyBeforePassingTheNotification() throws Exception {
        byte[] secret = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<A2aContracts.Notification> accepted = new AtomicReference<>();
        A2aNotificationController controller = new A2aNotificationController(
                new A2aNotificationProperties(true, "/unused", 4096),
                notification -> { accepted.set(notification); return java.util.concurrent.CompletableFuture.completedFuture(null); },
                mapper, secret);
        byte[] body = """
                {"agentRole":"developer","taskId":"agent-task-1","contextId":"context-1","sequence":2,
                 "state":"TASK_STATE_COMPLETED","occurredAt":"2026-09-06T12:00:00Z","artifacts":[]}
                """.getBytes(StandardCharsets.UTF_8);

        var response = controller.receive("sha256=" + hmac(secret, body), body).block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(accepted.get().state()).isEqualTo(A2aContracts.TaskState.COMPLETED);
        assertThatThrownBy(() -> controller.receive("sha256=" + "0".repeat(64), body).block())
                .isInstanceOf(SecurityException.class);
    }

    private static String hmac(byte[] secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
