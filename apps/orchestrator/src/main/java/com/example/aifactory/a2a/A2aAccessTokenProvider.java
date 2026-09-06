package com.example.aifactory.a2a;

import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Worker-local credential provider. Implementations must never log or persist the returned token. */
public interface A2aAccessTokenProvider {
    CompletionStage<char[]> acquire(String agentRole, Set<String> scopes);
}
