package com.example.aifactory.sandbox;

import com.example.aifactory.sandbox.service.SandboxRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
@Import(SandboxExecutionMcpIntegrationTest.RuntimeConfiguration.class)
class SandboxExecutionMcpIntegrationTest {
    private static final Path WORKSPACE_ROOT = temporaryDirectory();
    private static final Path STATE_ROOT = temporaryDirectory();
    private static String commit;
    private static String patchDigest;

    @Autowired
    ApplicationContext applicationContext;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ai-factory.sandbox.workspace-root", WORKSPACE_ROOT::toString);
        registry.add("ai-factory.sandbox.state-root", STATE_ROOT::toString);
    }

    @BeforeAll
    static void createRepository() throws Exception {
        Path repository = WORKSPACE_ROOT.resolve("integration-task");
        Files.createDirectories(repository);
        Files.writeString(repository.resolve("message.txt"), "before\n");
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "test@example.local");
        run(repository, "git", "config", "user.name", "Test");
        run(repository, "git", "add", "message.txt");
        run(repository, "git", "commit", "-qm", "initial");
        commit = output(repository, "git", "rev-parse", "HEAD");
        Path patch = repository.resolve("changes.patch");
        Files.writeString(patch, "diff --git a/message.txt b/message.txt\n--- a/message.txt\n+++ b/message.txt\n" +
                "@@ -1 +1 @@\n-before\n+after\n");
        patchDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(patch)));
    }

    @Test
    void negotiatesAndPublishesOnlyTheSevenAllowListedTools() {
        WebTestClient client = WebTestClient.bindToApplicationContext(applicationContext).build();

        client.post().uri("/mcp")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON, TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0", "id", 1, "method", "initialize",
                        "params", Map.of(
                                "protocolVersion", "2025-06-18",
                                "capabilities", Map.of(),
                                "clientInfo", Map.of("name", "integration-test", "version", "1.0.0"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.serverInfo.name").isEqualTo("sandbox-execution-mcp");

        client.post().uri("/mcp")
                .header("MCP-Protocol-Version", "2025-06-18")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON, TEXT_EVENT_STREAM)
                .bodyValue(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", Map.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.tools.length()").isEqualTo(7)
                .jsonPath("$.result.tools[?(@.name == 'sandbox.run_tests')]").exists()
                .jsonPath("$.result.tools[?(@.name == 'sandbox.cancel_execution')]").exists()
                .jsonPath("$.result.tools[?(@.name == 'sandbox.run_tests')].inputSchema.additionalProperties")
                .isEqualTo(false)
                .jsonPath("$.result.tools[?(@.name == 'sandbox.get_execution')].inputSchema.properties.output_cursor")
                .exists()
                .jsonPath("$.result.tools[?(@.name == 'sandbox.get_execution')].inputSchema.properties.output_limit")
                .exists()
                .jsonPath("$.result.tools[?(@.name == 'sandbox.validate_patch')].inputSchema.properties.run_id")
                .exists()
                .jsonPath("$.result.tools[?(@.name == 'sandbox.validate_patch')].inputSchema.properties.delegation_id")
                .exists()
                .jsonPath("$.result.tools[?(@.name == 'sandbox.validate_patch')].inputSchema.properties.agent_run_id")
                .exists();

        client.post().uri("/mcp")
                .header("MCP-Protocol-Version", "2025-06-18")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON, TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                        "params", Map.of(
                                "name", "sandbox.validate_patch",
                                "arguments", Map.ofEntries(
                                        Map.entry("schema_version", "1"),
                                        Map.entry("task_id", "integration-task"),
                                        Map.entry("attempt_id", "attempt-1"),
                                        Map.entry("source_commit", commit),
                                        Map.entry("actor", "workflow"),
                                        Map.entry("trace_id", "0123456789abcdef0123456789abcdef"),
                                        Map.entry("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-03"),
                                        Map.entry("run_id", "pipeline-1"),
                                        Map.entry("delegation_id", "workflow"),
                                        Map.entry("agent_run_id", "workflow-pipeline-1"),
                                        Map.entry("deadline", java.time.Instant.now().plusSeconds(60).toString()),
                                        Map.entry("idempotency_key", "integration-idempotency-key"),
                                        Map.entry("patch_digest", patchDigest)))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.content[0].text").value(containsString("\"execution_id\""))
                .jsonPath("$.result.content[0].text").value(containsString("\"created_at\":\""))
                .jsonPath("$.result.content[0].text").value(containsString("\"heartbeat_at\":\""))
                .jsonPath("$.result.content[0].text").value(containsString("\"evidence_status\":"));

        client.post().uri("/mcp")
                .header("MCP-Protocol-Version", "2025-06-18")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON, TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0", "id", 4, "method", "tools/call",
                        "params", Map.of(
                                "name", "sandbox.apply_patch",
                                "arguments", Map.of(
                                        "schema_version", "1",
                                        "task_id", "integration-task",
                                        "attempt_id", "attempt-1",
                                        "source_commit", commit,
                                        "actor", "workflow",
                                        "trace_id", "fedcba9876543210fedcba9876543210",
                                        "traceparent", "00-fedcba9876543210fedcba9876543210-0123456789abcdef-01",
                                        "deadline", java.time.Instant.now().plusSeconds(60).toString(),
                                        "idempotency_key", "integration-apply-idempotency-key",
                                        "patch_digest", patchDigest))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.content[0].text").value(containsString("\"operation\":\"APPLY_PATCH\""))
                .jsonPath("$.result.content[0].text").value(containsString("\"execution_id\""));
    }

    @Test
    void neverTreatsUnknownProfileOrCommandArgumentsAsExecutableInput() {
        WebTestClient client = WebTestClient.bindToApplicationContext(applicationContext).build();
        Path marker = WORKSPACE_ROOT.resolve("injected-by-mcp-argument");

        client.post().uri("/mcp")
                .header("MCP-Protocol-Version", "2025-06-18")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON, TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0", "id", 5, "method", "tools/call",
                        "params", Map.of(
                                "name", "sandbox.validate_patch",
                                "arguments", Map.ofEntries(
                                        Map.entry("schema_version", "1"),
                                        Map.entry("task_id", "integration-task"),
                                        Map.entry("attempt_id", "attempt-1"),
                                        Map.entry("source_commit", commit),
                                        Map.entry("actor", "workflow"),
                                        Map.entry("trace_id", "abcdef0123456789abcdef0123456789"),
                                        Map.entry("traceparent", "00-abcdef0123456789abcdef0123456789-0123456789abcdef-01"),
                                        Map.entry("deadline", java.time.Instant.now().plusSeconds(60).toString()),
                                        Map.entry("idempotency_key", "injected-profile-idempotency-key"),
                                        Map.entry("patch_digest", patchDigest),
                                        Map.entry("profile", "../../attacker-profile"),
                                        Map.entry("command", "touch " + marker)))))
                .exchange()
                .expectStatus().isOk();

        assertFalse(Files.exists(marker));
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("sandbox-execution-mcp-");
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
    }

    private static String output(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).strip();
        if (process.waitFor() != 0) {
            throw new IllegalStateException(output);
        }
        return output;
    }

    @TestConfiguration
    static class RuntimeConfiguration {
        @Bean
        @Primary
        SandboxRuntime fakeSandboxRuntime() {
            return new SandboxRuntime() {
                @Override
                public RuntimeResult execute(com.example.aifactory.sandbox.model.SandboxModels.Operation operation,
                                             String executionId, Path workspace) {
                    return new RuntimeResult(0, "ok");
                }

                @Override
                public void cancel(String executionId) {
                }
            };
        }
    }
}
