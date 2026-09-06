package com.example.aifactory.a2a;

import java.util.concurrent.CompletionStage;

/** Transport boundary whose credential argument exists only in worker memory. */
public interface A2aAuthenticatedSender {
    CompletionStage<A2aContracts.TaskSnapshot> send(A2aContracts.SendCommand command, char[] bearerToken);
}
