package com.example.aifactory.a2a;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

/** Small executable used only by the reproducible official-server interoperability qualification. */
public final class A2aInteropProbe {
    private A2aInteropProbe() {
    }

    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("Expected the reference A2A endpoint URL");
        A2aJsonRpcHttpTransport transport = A2aJsonRpcHttpTransport.forEndpoint(
                URI.create(args[0]), HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                new ObjectMapper());
        A2aContracts.SendCommand command = new A2aContracts.SendCommand(
                "developer", "tck", "tck-complete-task-project-client", null, null,
                List.of(new A2aContracts.Part(A2aMediaTypes.TEXT, "Project client interoperability probe",
                        Map.of(), null)), Map.of("probe", "A2A-163"), true);
        A2aContracts.TaskSnapshot result = transport.send(command, new char[0]).toCompletableFuture().join();
        for (int attempt = 0; attempt < 30 && !terminal(result.state()); attempt++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while polling the reference task", interrupted);
            }
            result = transport.getTask(new A2aContracts.TaskQuery("developer", result.taskId(), 0), new char[0])
                    .toCompletableFuture().join();
        }
        if (result.state() != A2aContracts.TaskState.COMPLETED) {
            throw new IllegalStateException("Reference server returned " + result.state());
        }
        System.out.printf("A2A-163 OK task=%s context=%s state=%s%n",
                result.taskId(), result.contextId(), result.state());
    }

    private static boolean terminal(A2aContracts.TaskState state) {
        return state == A2aContracts.TaskState.COMPLETED
                || state == A2aContracts.TaskState.FAILED
                || state == A2aContracts.TaskState.REJECTED
                || state == A2aContracts.TaskState.CANCELED;
    }
}
