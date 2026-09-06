package com.example.aifactory.a2a;

import java.util.concurrent.CompletionStage;

/** Server-side application port implemented by an independently addressable agent runtime. */
public interface A2aTaskServer {

    CompletionStage<A2aContracts.TaskSnapshot> send(A2aContracts.SendCommand command);

    CompletionStage<A2aContracts.TaskSnapshot> getTask(A2aContracts.TaskQuery query);

    CompletionStage<A2aContracts.TaskPage> listTasks(A2aContracts.TaskListQuery query);

    CompletionStage<A2aContracts.TaskSnapshot> cancelTask(A2aContracts.TaskQuery query);
}
