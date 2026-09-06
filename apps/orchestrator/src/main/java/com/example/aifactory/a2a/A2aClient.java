package com.example.aifactory.a2a;

import java.util.concurrent.CompletionStage;

/** Client-side application port for A2A operations used by Temporal activities. */
public interface A2aClient {

    CompletionStage<A2aContracts.TaskSnapshot> send(A2aContracts.SendCommand command);

    CompletionStage<A2aContracts.TaskSnapshot> getTask(A2aContracts.TaskQuery query);

    CompletionStage<A2aContracts.TaskSnapshot> cancelTask(A2aContracts.TaskQuery query);
}
