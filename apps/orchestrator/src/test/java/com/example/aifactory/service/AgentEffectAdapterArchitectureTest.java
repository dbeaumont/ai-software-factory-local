package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentEffectAdapterArchitectureTest {
    private static final Path SERVICES = Path.of("src/main/java/com/example/aifactory/service");
    private static final List<String> FORBIDDEN = List.of(
            "SandboxExecutor", "SandboxGateway", "McpSandboxService", "PatchIntegrator",
            "AssuranceGateway", "ScmDeliveryGateway", "ProcessRunner", "ProcessBuilder",
            "java.nio.file.Files");

    @Test
    void agentClassesDoNotDependOnEffectAdapters() throws Exception {
        List<Path> agents;
        try (var files = Files.list(SERVICES)) {
            agents = files.filter(path -> path.getFileName().toString().contains("Agent"))
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted().toList();
        }
        assertThat(agents).isNotEmpty();

        Map<String, List<String>> violations = agents.stream().collect(java.util.stream.Collectors.toMap(
                path -> path.getFileName().toString(),
                path -> FORBIDDEN.stream().filter(token -> contains(path, token)).toList()));
        assertThat(violations).allSatisfy((name, tokens) ->
                assertThat(tokens).as("effect adapter dependencies in %s", name).isEmpty());
    }

    private static boolean contains(Path path, String token) {
        try {
            return Files.readString(path).contains(token);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot inspect " + path, exception);
        }
    }
}
