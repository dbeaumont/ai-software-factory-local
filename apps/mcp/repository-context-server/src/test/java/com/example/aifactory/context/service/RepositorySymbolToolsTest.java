package com.example.aifactory.context.service;

import com.example.aifactory.context.config.RepositoryContextProperties;
import com.example.aifactory.context.model.ContextModels.GetSymbolsRequest;
import com.example.aifactory.context.model.ContextModels.GetSymbolsResult;
import com.example.aifactory.context.model.ContextModels.Symbol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepositorySymbolToolsTest {
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path root;

    private RepositorySymbolTools symbols;
    private String commit;

    @BeforeEach
    void setUp() throws Exception {
        Path repository = root.resolve("task-1");
        Files.createDirectories(repository.resolve("src"));
        Files.writeString(repository.resolve("src/Customer.java"), """
                package example;

                public record Customer(String name) {
                    private static final String TOKEN = "must-not-be-returned";

                    public Customer rename(String value) {
                        return new Customer(value);
                    }
                }
                """);
        Files.writeString(repository.resolve("src/customer.js"), """
                export class CustomerClient {
                  loadCustomer(id) { return id; }
                }
                export const customerLimit = 10;
                """);
        Files.writeString(repository.resolve("src/Customer.kt"), """
                class KotlinCustomer
                fun loadKotlinCustomer() = 1
                """);
        Files.writeString(repository.resolve("src/customer.ts"), """
                export class TypescriptCustomer { load(): number { return 1; } }
                """);
        Files.writeString(repository.resolve("src/customer.tsx"), """
                export function TsxCustomer() { return null; }
                """);
        Files.writeString(repository.resolve("src/customer.py"), """
                class PythonCustomer:
                    def load(self):
                        return 1
                """);
        Files.writeString(repository.resolve("src/customer.go"), """
                package sample
                func GoCustomer() int { return 1 }
                """);
        Files.writeString(repository.resolve("src/ignored.txt"), "class FakeSymbol {}\n");
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "test@example.local");
        run(repository, "git", "config", "user.name", "Test");
        run(repository, "git", "add", ".");
        run(repository, "git", "commit", "-qm", "initial");
        commit = output(repository, "git", "rev-parse", "HEAD");
        RepositoryContextProperties properties = new RepositoryContextProperties(
                root, root.resolve(".registry"), 1_048_576, 100, 100);
        RepositoryContextTools context = new RepositoryContextTools(properties, new TaskWorkspaceRegistry(properties));
        symbols = new RepositorySymbolTools(context);
    }

    @Test
    void indexesSymbolsByCommitAndParserVersionWithoutReturningBodiesOrLiterals() throws Exception {
        GetSymbolsResult result = symbols.getSymbols(request("src/Customer.java", null, "java", 100, null));

        assertEquals(commit, result.sourceCommit());
        assertEquals("tree-sitter-ng", result.parser().name());
        assertEquals("0.26.6+grammars.20260301", result.parser().version());
        assertTrue(result.symbols().stream().anyMatch(symbol -> symbol.name().equals("Customer")
                && symbol.kind().equals("RECORD") && symbol.startLine() == 3));
        assertTrue(result.symbols().stream().anyMatch(symbol -> symbol.name().equals("rename")
                && symbol.kind().equals("METHOD")));
        assertTrue(result.symbols().stream().anyMatch(symbol -> symbol.name().equals("TOKEN")
                && symbol.kind().equals("FIELD")));
        assertTrue(result.symbols().stream().map(Symbol::signature)
                .filter(java.util.Objects::nonNull).noneMatch(value -> value.contains("must-not-be-returned")));
        assertEquals(1, symbols.cachedIndexCount());

        symbols.getSymbols(request(null, "Customer", null, 100, null));
        assertEquals(1, symbols.cachedIndexCount(), "the same task, commit and parser version must reuse one index");
    }

    @Test
    void filtersByQueryAndLanguageAliasAndPaginatesWithOneTimeCursors() throws Exception {
        GetSymbolsResult first = symbols.getSymbols(request(null, "customer", "js", 1, null));

        assertEquals(1, first.symbols().size());
        assertEquals("javascript", first.symbols().getFirst().language());
        assertTrue(first.truncated());
        assertNotNull(first.nextCursor());

        GetSymbolsResult second = symbols.getSymbols(request(null, "customer", "js", 1, first.nextCursor()));
        assertFalse(second.symbols().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> symbols.getSymbols(request(null, "customer", "js", 1, first.nextCursor())));
    }

    @Test
    void loadsEveryPinnedGrammar() throws Exception {
        for (String language : List.of("java", "kotlin", "javascript", "typescript", "tsx", "python", "go")) {
            GetSymbolsResult result = symbols.getSymbols(request(null, "Customer", language, 100, null));
            assertFalse(result.symbols().isEmpty(), () -> "no symbol extracted for " + language);
            assertTrue(result.symbols().stream().allMatch(symbol -> symbol.language().equals(language)));
        }
    }

    @Test
    void rejectsUnsafeSelectionsUnauthorizedActorsAndCommitMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> symbols.getSymbols(request(null, null, null, 100, null)));
        assertThrows(IllegalArgumentException.class,
                () -> symbols.getSymbols(request("src", "Customer", null, 100, null)));
        assertThrows(IllegalArgumentException.class,
                () -> symbols.getSymbols(request("../outside", null, null, 100, null)));
        assertThrows(IllegalArgumentException.class,
                () -> symbols.getSymbols(request("src", null, "brainfuck", 100, null)));
        assertThrows(SecurityException.class, () -> symbols.getSymbols(new GetSymbolsRequest(
                "1", "task-1", commit, "unknown-role", TRACE_ID, "src", null, null, 100)));
        assertThrows(IllegalArgumentException.class, () -> symbols.getSymbols(new GetSymbolsRequest(
                "1", "task-1", "0".repeat(40), "planner", TRACE_ID, "src", null, null, 100)));
    }

    private GetSymbolsRequest request(String path, String query, String language, int maxResults, String cursor) {
        return new GetSymbolsRequest("1", "task-1", "attempt-test", commit, "planner", TRACE_ID,
                "00-" + TRACE_ID + "-0123456789abcdef-01", Instant.now().plusSeconds(60).toString(),
                path, query, language, maxResults, cursor);
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }

    private static String output(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).strip();
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
