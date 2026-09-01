package com.example.aifactory.context;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class RepositoryContextMcpIntegrationTest {
    private static final Path WORKSPACE_ROOT = temporaryDirectory();
    private static String commit;

    @Autowired
    ApplicationContext applicationContext;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ai-factory.context.workspace-root", WORKSPACE_ROOT::toString);
        registry.add("ai-factory.context.registry-root", () -> WORKSPACE_ROOT.resolve(".registry").toString());
    }

    @BeforeAll
    static void createRepository() throws Exception {
        Path repository = WORKSPACE_ROOT.resolve("integration-task");
        Files.createDirectories(repository.resolve("src"));
        Files.writeString(repository.resolve("src/Example.java"), "class Example {}\n");
        Files.writeString(repository.resolve("pom.xml"), """
                <project><dependencies><dependency>
                  <groupId>org.example</groupId><artifactId>integration-lib</artifactId><version>1.0</version>
                </dependency></dependencies></project>
                """);
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "test@example.local");
        run(repository, "git", "config", "user.name", "Test");
        run(repository, "git", "add", ".");
        run(repository, "git", "commit", "-qm", "initial");
        commit = output(repository, "git", "rev-parse", "HEAD");
    }

    @Test
    void negotiatesListsAndCallsRepositoryToolsWithoutOpeningANetworkSocket() {
        WebTestClient client = WebTestClient.bindToApplicationContext(applicationContext).build();

        client.post().uri("/mcp")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON, TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0",
                        "id", 1,
                        "method", "initialize",
                        "params", Map.of(
                                "protocolVersion", "2025-06-18",
                                "capabilities", Map.of(),
                                "clientInfo", Map.of("name", "integration-test", "version", "1.0.0"))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.serverInfo.name").isEqualTo("repository-context-mcp");

        client.post().uri("/mcp")
                .header("MCP-Protocol-Version", "2025-06-18")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON, TEXT_EVENT_STREAM)
                .bodyValue(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", Map.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.tools.length()").isEqualTo(5)
                .jsonPath("$.result.tools[?(@.name == 'context.read_file')]").exists()
                .jsonPath("$.result.tools[?(@.name == 'context.get_dependencies')]").exists();

        client.post().uri("/mcp")
                .header("MCP-Protocol-Version", "2025-06-18")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON, TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0",
                        "id", 3,
                        "method", "tools/call",
                        "params", Map.of(
                                "name", "context.read_file",
                                "arguments", Map.ofEntries(
                                        Map.entry("schema_version", "1"),
                                        Map.entry("task_id", "integration-task"),
                                        Map.entry("attempt_id", "attempt-1"),
                                        Map.entry("source_commit", commit),
                                        Map.entry("actor", "workflow"),
                                        Map.entry("trace_id", "0123456789abcdef0123456789abcdef"),
                                        Map.entry("traceparent", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01"),
                                        Map.entry("deadline", java.time.Instant.now().plusSeconds(60).toString()),
                                        Map.entry("path", "src/Example.java"),
                                        Map.entry("start_line", 1),
                                        Map.entry("max_bytes", 4096)))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.content[0].text").value(containsString("src/Example.java"))
                .jsonPath("$.result.content[0].text").value(containsString("class Example {}"))
                .jsonPath("$.result.content[0].text").value(containsString("\"start_line\":1"))
                .jsonPath("$.result.content[0].text").value(containsString("\"mime_type\":\"text/plain\""));

        client.post().uri("/mcp")
                .header("MCP-Protocol-Version", "2025-06-18")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON, TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0",
                        "id", 6,
                        "method", "tools/call",
                        "params", Map.of(
                                "name", "context.list_tree",
                                "arguments", Map.ofEntries(
                                        Map.entry("schema_version", "1"),
                                        Map.entry("task_id", "integration-task"),
                                        Map.entry("attempt_id", "attempt-1"),
                                        Map.entry("source_commit", commit),
                                        Map.entry("actor", "workflow"),
                                        Map.entry("trace_id", "1123456789abcdef0123456789abcdef"),
                                        Map.entry("traceparent", "00-1123456789abcdef0123456789abcdef-0123456789abcdef-01"),
                                        Map.entry("deadline", java.time.Instant.now().plusSeconds(60).toString()),
                                        Map.entry("path", ""),
                                        Map.entry("depth", 6),
                                        Map.entry("max_entries", 100)))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.content[0].text").value(containsString("\"source_commit\":\"" + commit + "\""))
                .jsonPath("$.result.content[0].text").value(containsString("\"next_cursor\""));

        client.post().uri("/mcp")
                .header("MCP-Protocol-Version", "2025-06-18")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON, TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0",
                        "id", 7,
                        "method", "tools/call",
                        "params", Map.of(
                                "name", "context.get_dependencies",
                                "arguments", Map.ofEntries(
                                        Map.entry("schema_version", "1"),
                                        Map.entry("task_id", "integration-task"),
                                        Map.entry("attempt_id", "attempt-1"),
                                        Map.entry("source_commit", commit),
                                        Map.entry("actor", "workflow"),
                                        Map.entry("trace_id", "2123456789abcdef0123456789abcdef"),
                                        Map.entry("traceparent", "00-2123456789abcdef0123456789abcdef-0123456789abcdef-01"),
                                        Map.entry("deadline", java.time.Instant.now().plusSeconds(60).toString()),
                                        Map.entry("module", "."),
                                        Map.entry("ecosystem", "MAVEN"),
                                        Map.entry("max_dependencies", 100)))))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.isError").isEqualTo(false)
                .jsonPath("$.result.content[0].text").value(containsString("org.example:integration-lib"))
                .jsonPath("$.result.content[0].text").value(containsString("\"declaration_path\":\"pom.xml\""));

        client.post().uri("/mcp")
                .header("MCP-Protocol-Version", "2025-06-18")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON, TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0", "id", 4, "method", "resources/templates/list", "params", Map.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.resourceTemplates[?(@.uriTemplate == 'repo://{task_id}/{source_commit}/{path}')]")
                .exists();

        client.post().uri("/mcp")
                .header("MCP-Protocol-Version", "2025-06-18")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON, TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "jsonrpc", "2.0", "id", 5, "method", "resources/read",
                        "params", Map.of("uri", "repo://integration-task/" + commit + "/src%2FExample.java")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.contents[0].text").value(containsString("class Example {}"));
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("repository-context-mcp-");
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
}
