package com.example.aifactory.workflow.temporal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDeterminismArchitectureTest {
    private static final Path WORKFLOW_SOURCES = Path.of(
            "src/main/java/com/example/aifactory/workflow/temporal");

    private static final Map<String, String> FORBIDDEN = forbiddenPatterns();

    @Test
    void workflowImplementationsContainNoDirectNondeterministicEffect() throws IOException {
        List<Path> implementations;
        try (var files = Files.list(WORKFLOW_SOURCES)) {
            implementations = files
                    .filter(path -> path.getFileName().toString().endsWith("WorkflowImpl.java"))
                    .sorted()
                    .toList();
        }

        assertThat(implementations).isNotEmpty();
        for (Path implementation : implementations) {
            String source = Files.readString(implementation);
            FORBIDDEN.forEach((pattern, rationale) -> assertThat(source)
                    .as("%s must not contain %s (%s)", implementation, pattern, rationale)
                    .doesNotContain(pattern));
        }
    }

    private static Map<String, String> forbiddenPatterns() {
        Map<String, String> patterns = new LinkedHashMap<>();
        patterns.put("java.io.", "direct filesystem or stream I/O");
        patterns.put("java.nio.file.", "direct filesystem I/O");
        patterns.put("java.net.", "direct network access");
        patterns.put("java.net.http.", "direct HTTP access");
        patterns.put("WebClient", "direct HTTP client access");
        patterns.put("McpToolInvoker", "MCP effects belong in Activities");
        patterns.put("LlmGatewayClient", "LLM effects belong in Activities");
        patterns.put("EvidenceRepository", "evidence persistence belongs in Activities");
        patterns.put("System.currentTimeMillis(", "system clock access");
        patterns.put("System.nanoTime(", "system clock access");
        patterns.put("Instant.now(", "system clock access");
        patterns.put("LocalDateTime.now(", "system clock access");
        patterns.put("OffsetDateTime.now(", "system clock access");
        patterns.put("ZonedDateTime.now(", "system clock access");
        patterns.put("UUID.randomUUID(", "direct randomness");
        patterns.put("new Random(", "direct randomness");
        patterns.put("ThreadLocalRandom", "direct randomness");
        patterns.put("SecureRandom", "direct randomness");
        patterns.put("ProcessBuilder", "direct process execution");
        patterns.put("Runtime.getRuntime(", "direct process/runtime access");
        return Map.copyOf(patterns);
    }
}
