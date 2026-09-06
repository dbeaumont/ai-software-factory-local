package com.example.aifactory.a2a;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** HTTP transport port; bearer tokens are intentionally absent from every A2A business contract. */
public interface A2aAuthenticatedClientTransport {
    CompletionStage<A2aContracts.TaskSnapshot> send(A2aContracts.SendCommand command, char[] bearerToken);
    CompletionStage<A2aContracts.TaskSnapshot> getTask(A2aContracts.TaskQuery query, char[] bearerToken);
    CompletionStage<A2aContracts.TaskSnapshot> cancelTask(A2aContracts.TaskQuery query, char[] bearerToken);
    CompletionStage<Optional<A2aContracts.TaskSnapshot>> findTaskByMessageId(
            String agentRole, String messageId, char[] bearerToken);
}
