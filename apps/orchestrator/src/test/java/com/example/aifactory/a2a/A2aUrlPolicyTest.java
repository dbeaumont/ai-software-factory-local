package com.example.aifactory.a2a;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aUrlPolicyTest {

    @Test
    void rejectsNonAllowListedLocalMetadataAndReboundNetworkTargets() throws Exception {
        URI allowed = URI.create("https://a2a-developer:8090/a2a");
        AtomicReference<String> address = new AtomicReference<>("192.0.2.20");
        SecureUriPolicy policy = new SecureUriPolicy(Set.of(allowed), host ->
                List.of(InetAddress.getByName(address.get())));

        assertThat(policy.requireAllowed(allowed)).isEqualTo(allowed);
        assertThatThrownBy(() -> policy.requireAllowed(URI.create("https://attacker.invalid/a2a")))
                .isInstanceOf(SecurityException.class).hasMessageContaining("allow-listed");
        address.set("169.254.169.254");
        assertThatThrownBy(() -> policy.requireAllowed(allowed)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> SecureUriPolicy.system(Set.of(URI.create("https://localhost/a2a"))))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void acceptsOnlyEvidenceReferencesBoundToTaskAttemptAndDigest() {
        String digest = "a".repeat(64);
        String valid = "evidence://agent-task-1/attempt-1/agent-result/" + digest;
        assertThat(A2aEvidenceUriPolicy.requireBound(
                valid, "agent-task-1", "attempt-1", digest).toString()).isEqualTo(valid);
        assertThatThrownBy(() -> A2aEvidenceUriPolicy.requireBound(
                "evidence://other-task/attempt-1/agent-result/" + digest,
                "agent-task-1", "attempt-1", digest)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> A2aEvidenceUriPolicy.requireBound(
                valid + "?redirect=https://attacker.invalid", "agent-task-1", "attempt-1", digest))
                .isInstanceOf(SecurityException.class);
    }
}
