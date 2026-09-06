package com.example.aifactory.a2a;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class A2aJsonRpcHttpTransportTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void exchangesA2aOnePointZeroJsonRpcWithoutLeakingWireTypes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            assertThat(exchange.getRequestHeaders().getFirst("A2A-Version")).isEqualTo("1.0");
            assertThat(request.path("method").asText()).isEqualTo("SendMessage");
            assertThat(request.path("params").path("configuration").path("returnImmediately").asBoolean()).isTrue();
            byte[] response = mapper.writeValueAsBytes(Map.of(
                    "jsonrpc", "2.0", "id", request.path("id").asText(),
                    "result", Map.of("task", Map.of(
                            "id", "reference-task", "contextId", "reference-context",
                            "status", Map.of("state", "TASK_STATE_COMPLETED",
                                    "timestamp", "2026-09-06T18:00:00Z")))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/a2a");
        A2aJsonRpcHttpTransport transport = A2aJsonRpcHttpTransport.forEndpoint(
                endpoint, HttpClient.newHttpClient(), mapper);

        A2aContracts.TaskSnapshot task = transport.send(new A2aContracts.SendCommand(
                "developer", "tck", "interop-message", null, null,
                List.of(new A2aContracts.Part(A2aMediaTypes.TEXT, "hello", Map.of(), null)),
                Map.of(), true), new char[0]).toCompletableFuture().join();

        assertThat(task.taskId()).isEqualTo("reference-task");
        assertThat(task.state()).isEqualTo(A2aContracts.TaskState.COMPLETED);
    }
}
