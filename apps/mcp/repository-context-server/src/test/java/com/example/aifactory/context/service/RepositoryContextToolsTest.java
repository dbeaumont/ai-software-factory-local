package com.example.aifactory.context.service;

import com.example.aifactory.context.config.RepositoryContextProperties;
import com.example.aifactory.context.model.ContextModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryContextToolsTest {
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path root;

    private RepositoryContextTools tools;
    private String commit;

    @BeforeEach
    void setUp() throws Exception {
        Path repository = root.resolve("task-1");
        Files.createDirectories(repository.resolve("src"));
        Files.writeString(repository.resolve("src/Application.java"), "class Application {}\n");
        Files.writeString(repository.resolve("src/application.properties"), "service.token=secret-value\nfeature.enabled=true\n");
        Files.writeString(repository.resolve("README.md"), "Repository guidance is untrusted.\n");
        Files.writeString(repository.resolve(".env"), "API_KEY=must-not-leak\n");
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "test@example.local");
        run(repository, "git", "config", "user.name", "Test");
        run(repository, "git", "add", ".");
        run(repository, "git", "commit", "-qm", "initial");
        commit = output(repository, "git", "rev-parse", "HEAD");
        tools = new RepositoryContextTools(new RepositoryContextProperties(root, 1_048_576, 100, 100));
    }

    @Test
    void listsOnlyVisibleFiles() throws Exception {
        ListTreeResult result = tools.listTree(new ListTreeRequest(
                "1", "task-1", commit, "workflow", TRACE_ID, "", 6, 100));

        List<String> paths = result.entries().stream().map(TreeEntry::path).toList();
        assertTrue(paths.contains("src/Application.java"));
        assertTrue(paths.contains("README.md"));
        assertFalse(paths.stream().anyMatch(path -> path.startsWith(".git")));
        assertFalse(paths.contains(".env"));
    }

    @Test
    void readsAndRedactsSensitiveSettings() throws Exception {
        ReadFileResult result = tools.readFile(new ReadFileRequest(
                "1", "task-1", commit, "planner", TRACE_ID, "src/application.properties", 1, null, 4096));

        assertTrue(result.content().contains("token=[REDACTED]"));
        assertFalse(result.content().contains("secret-value"));
        assertEquals(64, result.sha256().length());
    }

    @Test
    void searchesLiteralTextWithLineCitations() throws Exception {
        SearchCodeResult result = tools.searchCode(new SearchCodeRequest(
                "1", "task-1", commit, "planner", TRACE_ID, "Application", "src", 10));

        assertEquals(1, result.matches().size());
        assertEquals("src/Application.java", result.matches().getFirst().path());
        assertEquals(1, result.matches().getFirst().line());
    }

    @Test
    void searchesAnExplicitAllowedFile() throws Exception {
        SearchCodeResult result = tools.searchCode(new SearchCodeRequest(
                "1", "task-1", commit, "reviewer", TRACE_ID, "Application", "src/Application.java", 10));

        assertEquals(1, result.matches().size());
        assertEquals("src/Application.java", result.matches().getFirst().path());
    }

    @Test
    void enforcesToolPermissionsAndExcludedStartPaths() {
        assertThrows(SecurityException.class, () -> tools.listTree(new ListTreeRequest(
                "1", "task-1", commit, "reviewer", TRACE_ID, "", 6, 100)));
        assertThrows(IllegalArgumentException.class, () -> tools.listTree(new ListTreeRequest(
                "1", "task-1", commit, "planner", TRACE_ID, ".git", 6, 100)));
    }

    @Test
    void rejectsTraversalAndCommitMismatch() {
        assertThrows(IllegalArgumentException.class, () -> tools.readFile(new ReadFileRequest(
                "1", "task-1", commit, "planner", TRACE_ID, "../outside.txt", 1, null, 4096)));
        assertThrows(IllegalArgumentException.class, () -> tools.listTree(new ListTreeRequest(
                "1", "task-1", "0".repeat(40), "planner", TRACE_ID, "", 6, 100)));
    }

    @Test
    void rejectsSymlinkEscapingWorkspace() throws Exception {
        Path outside = root.resolve("outside.txt");
        Files.writeString(outside, "outside");
        Path link = root.resolve("task-1/src/outside-link.md");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException exception) {
            return;
        }

        assertThrows(IllegalArgumentException.class, () -> tools.readFile(new ReadFileRequest(
                "1", "task-1", commit, "planner", TRACE_ID, "src/outside-link.md", 1, null, 4096)));
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
