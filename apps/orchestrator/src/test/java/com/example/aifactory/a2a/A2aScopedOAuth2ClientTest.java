package com.example.aifactory.a2a;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class A2aScopedOAuth2ClientTest {

    @Test
    void acquiresLeastPrivilegeTokenPerOperationAndClearsItAfterTransportUse() {
        AtomicReference<Set<String>> requestedScopes = new AtomicReference<>();
        AtomicReference<char[]> observedToken = new AtomicReference<>();
        A2aAccessTokenProvider tokens = (role, scopes) -> {
            assertThat(role).isEqualTo("developer");
            requestedScopes.set(scopes);
            return CompletableFuture.completedFuture("worker-only-token-value".toCharArray());
        };
        A2aAuthenticatedClientTransport transport = new A2aAuthenticatedClientTransport() {
            @Override public java.util.concurrent.CompletionStage<A2aContracts.TaskSnapshot> send(
                    A2aContracts.SendCommand command, char[] bearerToken) {
                observedToken.set(bearerToken);
                assertThat(new String(bearerToken)).isEqualTo("worker-only-token-value");
                return CompletableFuture.completedFuture(snapshot());
            }
            @Override public java.util.concurrent.CompletionStage<A2aContracts.TaskSnapshot> getTask(
                    A2aContracts.TaskQuery query, char[] bearerToken) {
                return CompletableFuture.completedFuture(snapshot());
            }
            @Override public java.util.concurrent.CompletionStage<A2aContracts.TaskSnapshot> cancelTask(
                    A2aContracts.TaskQuery query, char[] bearerToken) {
                return CompletableFuture.completedFuture(snapshot());
            }
            @Override public java.util.concurrent.CompletionStage<Optional<A2aContracts.TaskSnapshot>>
            findTaskByMessageId(String agentRole, String messageId, char[] bearerToken) {
                return CompletableFuture.completedFuture(Optional.of(snapshot()));
            }
        };
        A2aScopedOAuth2Client client = new A2aScopedOAuth2Client(tokens, transport);

        client.send(command()).toCompletableFuture().join();

        assertThat(requestedScopes.get()).containsExactlyInAnyOrder(
                "a2a.invoke", "a2a.role.developer", "a2a.skill.developer.code-task-v1");
        assertThat(observedToken.get()).containsOnly('\0');
        assertThat(command().metadata()).doesNotContainKeys("token", "access_token", "authorization");
    }

    private static A2aContracts.SendCommand command() {
        return new A2aContracts.SendCommand("developer", "developer.code-task-v1", "message-1", null, null,
                List.of(new A2aContracts.Part(A2aMediaTypes.JSON, null, Map.of("instruction", "change"), null)),
                Map.of(), true);
    }

    private static A2aContracts.TaskSnapshot snapshot() {
        return new A2aContracts.TaskSnapshot("task-1", "context-1", A2aContracts.TaskState.SUBMITTED,
                Instant.parse("2026-09-06T12:00:00Z"), List.of(), Map.of());
    }
}
