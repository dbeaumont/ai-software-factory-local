package com.example.aifactory.a2a;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/** Adds an operation- and role-scoped short-lived token entirely inside the activity-side client. */
public final class A2aScopedOAuth2Client implements A2aClient {
    private final A2aAccessTokenProvider tokens;
    private final A2aAuthenticatedClientTransport transport;

    public A2aScopedOAuth2Client(A2aAccessTokenProvider tokens, A2aAuthenticatedClientTransport transport) {
        this.tokens = tokens;
        this.transport = transport;
    }

    @Override
    public CompletionStage<A2aContracts.TaskSnapshot> send(A2aContracts.SendCommand command) {
        return authenticated(command.agentRole(), Set.of(
                "a2a.invoke", "a2a.role." + command.agentRole(), "a2a.skill." + command.skillId()),
                token -> transport.send(command, token));
    }

    @Override
    public CompletionStage<A2aContracts.TaskSnapshot> getTask(A2aContracts.TaskQuery query) {
        return authenticated(query.agentRole(), Set.of("a2a.read", "a2a.role." + query.agentRole()),
                token -> transport.getTask(query, token));
    }

    @Override
    public CompletionStage<A2aContracts.TaskSnapshot> cancelTask(A2aContracts.TaskQuery query) {
        return authenticated(query.agentRole(), Set.of("a2a.cancel", "a2a.role." + query.agentRole()),
                token -> transport.cancelTask(query, token));
    }

    @Override
    public CompletionStage<Optional<A2aContracts.TaskSnapshot>> findTaskByMessageId(
            String agentRole, String messageId) {
        return authenticated(agentRole, Set.of("a2a.read", "a2a.role." + agentRole),
                token -> transport.findTaskByMessageId(agentRole, messageId, token));
    }

    private <T> CompletionStage<T> authenticated(
            String role, Set<String> scopes, Function<char[], CompletionStage<T>> operation) {
        return tokens.acquire(role, scopes).thenCompose(token -> {
            if (token == null || token.length < 16) {
                if (token != null) Arrays.fill(token, '\0');
                throw new SecurityException("A2A OAuth2 access token is unavailable");
            }
            try {
                return operation.apply(token).whenComplete((ignored, failure) -> Arrays.fill(token, '\0'));
            } catch (RuntimeException failure) {
                Arrays.fill(token, '\0');
                throw failure;
            }
        });
    }
}
