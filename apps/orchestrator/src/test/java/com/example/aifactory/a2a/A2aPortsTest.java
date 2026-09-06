package com.example.aifactory.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class A2aPortsTest {

    private static final Set<Class<?>> PORTS = Set.of(
            A2aClient.class,
            A2aTaskServer.class,
            AgentCardResolver.class,
            A2aNotificationReceiver.class);

    @Test
    void portsDoNotExposeSdkFrameworkOrTemporalTypes() {
        List<String> forbiddenPrefixes = List.of(
                "org.a2aproject.",
                "org.springframework.",
                "reactor.",
                "io.temporal.");

        List<String> exposedTypes = PORTS.stream()
                .flatMap(type -> Stream.of(type.getMethods()))
                .flatMap(A2aPortsTest::signatureTypes)
                .map(Class::getName)
                .toList();

        assertTrue(exposedTypes.stream()
                .noneMatch(name -> forbiddenPrefixes.stream().anyMatch(name::startsWith)), exposedTypes.toString());
    }

    @Test
    void contractsDefensivelyCopyCollections() {
        List<A2aContracts.Part> parts = new ArrayList<>();
        Map<String, Object> metadata = new HashMap<>();
        parts.add(new A2aContracts.Part("text/plain", "instruction", Map.of(), null));
        metadata.put("correlation", "value");

        A2aContracts.SendCommand command = new A2aContracts.SendCommand(
                "supervisor", "plan", "message-1", null, null, parts, metadata, true);
        parts.clear();
        metadata.clear();

        assertEquals(1, command.parts().size());
        assertEquals("value", command.metadata().get("correlation"));
        assertThrows(UnsupportedOperationException.class, () -> command.parts().clear());
        assertThrows(UnsupportedOperationException.class, () -> command.metadata().clear());
    }

    @Test
    void contractsRejectIncompleteTasksAndParts() {
        assertThrows(IllegalArgumentException.class,
                () -> new A2aContracts.Part("application/json", null, Map.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new A2aContracts.Part("application/octet-stream", "raw", Map.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new A2aContracts.Part("text/plain", "text", Map.of("unexpected", true), null));
        assertEquals(Map.of("contract", "plan-v1"), new A2aContracts.Part(
                A2aMediaTypes.EVIDENCE_REFERENCE,
                null,
                Map.of("contract", "plan-v1"),
                URI.create("evidence://task-1/attempt-1/plan/abc")).data());
        assertThrows(IllegalArgumentException.class,
                () -> new A2aContracts.SendCommand("supervisor", "plan", "message-1",
                        null, null, List.of(), Map.of(), true));
        assertThrows(IllegalArgumentException.class,
                () -> new A2aContracts.SendCommand("supervisor", "plan", "message-1",
                        null, null, List.of(new A2aContracts.Part("text/plain", "instruction", Map.of(), null)),
                        Map.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> new A2aContracts.TaskQuery("supervisor", "task-1", -1));

        A2aContracts.TaskSnapshot snapshot = new A2aContracts.TaskSnapshot(
                "task-1", "context-1", A2aContracts.TaskState.SUBMITTED, Instant.EPOCH, List.of(), Map.of());
        assertEquals(URI.create("https://agent.example/a2a"), new A2aContracts.AgentCardDescriptor(
                "supervisor",
                URI.create("https://agent.example/.well-known/agent-card.json"),
                URI.create("https://agent.example/a2a"),
                "JSONRPC", "1.0", "sha256:abc", List.of("plan"), false, true).endpoint());
        assertThrows(IllegalArgumentException.class, () -> new A2aContracts.AgentCardDescriptor(
                "supervisor",
                URI.create("https://agent.example/.well-known/agent-card.json"),
                URI.create("https://agent.example/a2a"),
                "JSONRPC", "1.0", "sha256:abc", List.of("plan"), true, true));
        assertEquals(A2aContracts.TaskState.SUBMITTED, snapshot.state());
    }

    @Test
    void initialMediaTypeSetIsExplicitAndClosed() {
        assertTrue(A2aMediaTypes.isSupported("text/plain"));
        assertTrue(A2aMediaTypes.isSupported("application/json"));
        assertTrue(A2aMediaTypes.isSupported("application/vnd.ai-factory.evidence-reference+json"));
        assertTrue(!A2aMediaTypes.isSupported("application/octet-stream"));
    }

    private static Stream<Class<?>> signatureTypes(Method method) {
        return Stream.concat(Stream.of(method.getReturnType()), Stream.of(method.getParameterTypes()));
    }
}
