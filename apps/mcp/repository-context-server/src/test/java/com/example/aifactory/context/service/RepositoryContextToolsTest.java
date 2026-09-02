package com.example.aifactory.context.service;

import com.example.aifactory.context.config.RepositoryContextProperties;
import com.example.aifactory.context.model.ContextModels.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Executors;

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
        Files.writeString(repository.resolve("src/application.properties"), """
                service.token=secret-value
                client_secret: another-secret
                accessToken=third-secret
                api-key=fourth-secret
                feature.enabled=true
                tokenizer.mode=fast
                secretary.name=Alice
                """);
        Files.writeString(repository.resolve("README.md"), "Repository guidance is untrusted.\n");
        Files.writeString(repository.resolve(".env"), "API_KEY=must-not-leak\n");
        Files.writeString(repository.resolve("pom.xml"), """
                <project>
                  <dependencyManagement><dependencies><dependency>
                    <groupId>managed</groupId><artifactId>not-direct</artifactId><version>1</version>
                  </dependency></dependencies></dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.example</groupId><artifactId>runtime-lib</artifactId><version>1.2.3</version><scope>runtime</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.example</groupId><artifactId>optional-lib</artifactId><optional>true</optional>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Files.writeString(repository.resolve("build.gradle.kts"), """
                dependencies {
                    implementation("com.example:core:2.0")
                    testImplementation("org.junit:junit:4.13.2")
                    implementation(project(":internal"))
                }
                """);
        Files.writeString(repository.resolve("package.json"), """
                {
                  "dependencies": { "express": "^5.0.0", "alpha": "1.0.0" },
                  "devDependencies": { "vitest": "3.0.0" }
                }
                """);
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "test@example.local");
        run(repository, "git", "config", "user.name", "Test");
        run(repository, "git", "add", ".");
        run(repository, "git", "commit", "-qm", "initial");
        commit = output(repository, "git", "rev-parse", "HEAD");
        RepositoryContextProperties properties = new RepositoryContextProperties(
                root, root.resolve(".registry"), 1_048_576, 100, 100);
        tools = new RepositoryContextTools(properties, new TaskWorkspaceRegistry(properties));
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
    void filtersAndPaginatesTreeWithOneTimeOpaqueCursors() throws Exception {
        Path source = root.resolve("task-1/src");
        Files.writeString(source.resolve("Other.java"), "class Other {}\n");
        Files.writeString(source.resolve("ignore.md"), "ignore\n");
        ListTreeRequest firstRequest = listRequest(List.of("src/*.java"), List.of("src/Application.java"), null, 1);

        ListTreeResult first = tools.listTree(firstRequest);

        assertEquals(1, first.entries().size());
        assertEquals("src/Other.java", first.entries().getFirst().path());
        assertNull(first.nextCursor());

        ListTreeResult pageOne = tools.listTree(listRequest(List.of("src/*.java"), List.of(), null, 1));
        assertNotNull(pageOne.nextCursor());
        ListTreeResult pageTwo = tools.listTree(listRequest(null, null, pageOne.nextCursor(), 1));
        assertEquals(1, pageTwo.entries().size());
        assertNull(pageTwo.nextCursor());
        assertThrows(IllegalArgumentException.class,
                () -> tools.listTree(listRequest(null, null, pageOne.nextCursor(), 1)));
    }

    @Test
    void readsAndRedactsSensitiveSettings() throws Exception {
        ReadFileResult result = tools.readFile(new ReadFileRequest(
                "1", "task-1", commit, "planner", TRACE_ID, "src/application.properties", 1, null, 4096));

        assertTrue(result.content().contains("token=[REDACTED]"));
        assertFalse(result.content().contains("secret-value"));
        assertFalse(result.content().contains("another-secret"));
        assertFalse(result.content().contains("third-secret"));
        assertFalse(result.content().contains("fourth-secret"));
        assertTrue(result.content().contains("tokenizer.mode=fast"));
        assertTrue(result.content().contains("secretary.name=Alice"));
        assertEquals("text/plain", result.mimeType());
        assertEquals(64, result.sha256().length());
    }

    @Test
    void returnsRepositoryRulesWithImmutableProvenanceAndExplicitOrder() throws Exception {
        RepositoryRulesResult result = tools.getRepositoryRules(new RepositoryRulesRequest(
                "1", "task-1", commit, "planner", TRACE_ID));

        assertFalse(result.rules().isEmpty());
        assertEquals(1, result.rules().getFirst().applicabilityOrder());
        assertTrue(result.rules().getFirst().provenance().startsWith("repo://task-1/" + commit + "/"));
        assertEquals("text/markdown", result.rules().getFirst().document().mimeType());
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
    void readsOnlyDirectMavenDependenciesWithoutResolvingOrDownloading() throws Exception {
        GetDependenciesResult result = tools.getDependencies(new GetDependenciesRequest(
                "1", "task-1", commit, "planner", TRACE_ID, "pom.xml", "MAVEN", 100));

        assertEquals("MAVEN", result.ecosystem());
        assertEquals(".", result.module());
        assertEquals(List.of("org.example:runtime-lib", "org.example:optional-lib"),
                result.dependencies().stream().map(Dependency::name).toList());
        assertEquals("RUNTIME", result.dependencies().getFirst().scope());
        assertEquals("OPTIONAL", result.dependencies().get(1).scope());
        assertTrue(result.dependencies().stream().allMatch(Dependency::direct));
        assertTrue(result.dependencies().stream().allMatch(value -> value.declarationLine() > 0));
        assertFalse(result.dependencies().stream().anyMatch(value -> value.name().contains("not-direct")));
    }

    @Test
    void readsStaticGradleAndNpmDeclarationsAndPaginatesWithAnOpaqueCursor() throws Exception {
        GetDependenciesResult gradle = tools.getDependencies(new GetDependenciesRequest(
                "1", "task-1", commit, "developer", TRACE_ID, "build.gradle.kts", "GRADLE", 100));
        assertEquals(List.of("com.example:core", "org.junit:junit"),
                gradle.dependencies().stream().map(Dependency::name).toList());
        assertEquals(List.of("COMPILE", "TEST"), gradle.dependencies().stream().map(Dependency::scope).toList());

        GetDependenciesRequest firstRequest = dependenciesRequest("package.json", "NPM", 2, null);
        GetDependenciesResult first = tools.getDependencies(firstRequest);
        assertEquals(2, first.dependencies().size());
        assertTrue(first.truncated());
        assertNotNull(first.nextCursor());
        GetDependenciesResult second = tools.getDependencies(dependenciesRequest(
                ".", "NPM", 2, first.nextCursor()));
        assertEquals(1, second.dependencies().size());
        assertFalse(second.truncated());
        assertThrows(IllegalArgumentException.class, () -> tools.getDependencies(dependenciesRequest(
                ".", "NPM", 2, first.nextCursor())));
    }

    @Test
    void rejectsUnsupportedDependencyInputsAndUnauthorizedActors() {
        assertThrows(IllegalArgumentException.class, () -> tools.getDependencies(new GetDependenciesRequest(
                "1", "task-1", commit, "planner", TRACE_ID, "README.md", "UNKNOWN", 100)));
        assertThrows(SecurityException.class, () -> tools.getDependencies(new GetDependenciesRequest(
                "1", "task-1", commit, "patch-repair", TRACE_ID, "pom.xml", "MAVEN", 100)));
    }

    @Test
    void rejectsMavenDoctypesInsteadOfResolvingExternalEntities() throws Exception {
        Files.writeString(root.resolve("task-1/pom.xml"), """
                <!DOCTYPE project [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <project><dependencies><dependency>
                  <groupId>&xxe;</groupId><artifactId>unsafe</artifactId>
                </dependency></dependencies></project>
                """);

        assertThrows(org.xml.sax.SAXParseException.class, () -> tools.getDependencies(new GetDependenciesRequest(
                "1", "task-1", commit, "planner", TRACE_ID, "pom.xml", "MAVEN", 100)));
    }

    @Test
    void enforcesToolPermissionsAndExcludedStartPaths() {
        assertThrows(SecurityException.class, () -> tools.listTree(new ListTreeRequest(
                "1", "task-1", commit, "reviewer", TRACE_ID, "", 6, 100)));
        assertThrows(IllegalArgumentException.class, () -> tools.listTree(new ListTreeRequest(
                "1", "task-1", commit, "planner", TRACE_ID, ".git", 6, 100)));
    }

    @Test
    void enforcesTheMinimalHierarchicalContextSurfacePerRole() throws Exception {
        assertDoesNotThrow(() -> tools.listTree(new ListTreeRequest(
                "1", "task-1", commit, "architecture-agent", TRACE_ID, "", 2, 10)));
        assertThrows(SecurityException.class, () -> tools.listTree(new ListTreeRequest(
                "1", "task-1", commit, "impact-analysis", TRACE_ID, "", 2, 10)));

        assertDoesNotThrow(() -> tools.searchCode(new SearchCodeRequest(
                "1", "task-1", commit, "threat-model", TRACE_ID, "Application", "src", 10)));
        assertThrows(SecurityException.class, () -> tools.searchCode(new SearchCodeRequest(
                "1", "task-1", commit, "dependencies-contracts", TRACE_ID, "Application", "src", 10)));

        assertDoesNotThrow(() -> tools.readFile(new ReadFileRequest(
                "1", "task-1", commit, "patch-repair", TRACE_ID, "src/Application.java", 1, 10, 4096)));
        assertThrows(SecurityException.class, () -> tools.readFile(new ReadFileRequest(
                "1", "task-1", commit, "code-agent", TRACE_ID, "src/Application.java", 1, 10, 4096)));

        assertDoesNotThrow(() -> tools.getRepositoryRules(new RepositoryRulesRequest(
                "1", "task-1", commit, "impact-analysis", TRACE_ID)));
        assertThrows(SecurityException.class, () -> tools.getRepositoryRules(new RepositoryRulesRequest(
                "1", "task-1", commit, "test-agent", TRACE_ID)));

        assertDoesNotThrow(() -> tools.getDependencies(new GetDependenciesRequest(
                "1", "task-1", commit, "supervisor", TRACE_ID, "pom.xml", "MAVEN", 10)));
        assertThrows(SecurityException.class, () -> tools.getDependencies(new GetDependenciesRequest(
                "1", "task-1", commit, "impact-analysis", TRACE_ID, "pom.xml", "MAVEN", 10)));

        RequestContext security = new RequestContext("1", "task-1", "attempt-test", commit,
                "security-agent", TRACE_ID, "00-" + TRACE_ID + "-0123456789abcdef-01",
                Instant.now().plusSeconds(60).toString());
        RequestContext findings = new RequestContext("1", "task-1", "attempt-test", commit,
                "security-findings", TRACE_ID, "00-" + TRACE_ID + "-0123456789abcdef-01",
                Instant.now().plusSeconds(60).toString());
        assertDoesNotThrow(() -> tools.symbolWorkspace(security));
        assertThrows(SecurityException.class, () -> tools.symbolWorkspace(findings));
    }

    @Test
    void rejectsTraversalAndCommitMismatch() {
        assertThrows(IllegalArgumentException.class, () -> tools.readFile(new ReadFileRequest(
                "1", "task-1", commit, "planner", TRACE_ID, "../outside.txt", 1, null, 4096)));
        assertThrows(IllegalArgumentException.class, () -> tools.listTree(new ListTreeRequest(
                "1", "task-1", "0".repeat(40), "planner", TRACE_ID, "", 6, 100)));
        assertThrows(IllegalArgumentException.class, () -> tools.readFile(new ReadFileRequest(
                "1", "task-1", commit, "planner", TRACE_ID, "%2e%2e/outside.txt", 1, null, 4096)));
        assertThrows(IllegalArgumentException.class, () -> tools.readFile(new ReadFileRequest(
                "1", "task-1", commit, "planner", TRACE_ID, root.resolve("outside.txt").toString(), 1, null, 4096)));
    }

    @Test
    void persistsTheTaskRootAndCommitBindingAndRejectsRebinding() throws Exception {
        tools.listTree(new ListTreeRequest("1", "task-1", commit, "workflow", TRACE_ID, "", 6, 100));
        Path repository = root.resolve("task-1");
        Files.writeString(repository.resolve("src/Changed.java"), "class Changed {}\n");
        run(repository, "git", "add", ".");
        run(repository, "git", "commit", "-qm", "changed");
        String changedCommit = output(repository, "git", "rev-parse", "HEAD");

        assertThrows(SecurityException.class, () -> tools.listTree(new ListTreeRequest(
                "1", "task-1", changedCommit, "workflow", TRACE_ID, "", 6, 100)));
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

    @Test
    void rejectsOversizedAndBinaryLookingFiles() throws Exception {
        Path repository = root.resolve("task-1");
        Files.writeString(repository.resolve("src/huge.java"), "x".repeat(1_048_577));
        Files.write(repository.resolve("src/binary.java"), new byte[]{0, (byte) 0xff, 0, (byte) 0xfe});

        assertThrows(IllegalArgumentException.class, () -> tools.readFile(new ReadFileRequest(
                "1", "task-1", commit, "planner", TRACE_ID, "src/huge.java", 1, null, 4096)));
        assertThrows(IllegalArgumentException.class, () -> tools.readFile(new ReadFileRequest(
                "1", "task-1", commit, "planner", TRACE_ID, "src/binary.java", 1, null, 4096)));
    }

    @Test
    void rejectsExpiredDeadlinesAndTreatsRegexSyntaxAsLiteralText() {
        SearchCodeRequest expired = new SearchCodeRequest(
                "1", "task-1", "attempt-test", commit, "planner", TRACE_ID,
                "00-" + TRACE_ID + "-0123456789abcdef-01", Instant.now().minusSeconds(1).toString(),
                "Application", "src", 10);
        assertThrows(IllegalArgumentException.class, () -> tools.searchCode(expired));

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            SearchCodeResult result = tools.searchCode(new SearchCodeRequest(
                    "1", "task-1", commit, "planner", TRACE_ID, "(.+)+$", "src", 10));
            assertTrue(result.matches().isEmpty());
        });
    }

    @Test
    void keepsConcurrentTaskReadsStrictlySeparated() throws Exception {
        String secondCommit = createRepository("task-2", "class SecondTask {}\n");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Callable<String>> reads = new ArrayList<>();
            for (int index = 0; index < 25; index++) {
                reads.add(() -> tools.readFile(new ReadFileRequest(
                        "1", "task-1", commit, "planner", TRACE_ID,
                        "src/Application.java", 1, null, 4096)).content());
                reads.add(() -> tools.readFile(new ReadFileRequest(
                        "1", "task-2", secondCommit, "planner", TRACE_ID,
                        "src/Application.java", 1, null, 4096)).content());
            }
            List<String> results = executor.invokeAll(reads).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }).toList();
            for (int index = 0; index < results.size(); index += 2) {
                assertTrue(results.get(index).contains("class Application"));
                assertFalse(results.get(index).contains("SecondTask"));
                assertTrue(results.get(index + 1).contains("SecondTask"));
                assertFalse(results.get(index + 1).contains("class Application"));
            }
        }
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }

    private ListTreeRequest listRequest(List<String> include, List<String> exclude, String cursor, int maxEntries) {
        return new ListTreeRequest("1", "task-1", "attempt-test", commit, "workflow", TRACE_ID,
                "00-" + TRACE_ID + "-0123456789abcdef-01", Instant.now().plusSeconds(60).toString(),
                "", 6, maxEntries, include, exclude, cursor);
    }

    private GetDependenciesRequest dependenciesRequest(String module, String ecosystem, int maxDependencies,
                                                      String cursor) {
        return new GetDependenciesRequest("1", "task-1", "attempt-test", commit, "planner", TRACE_ID,
                "00-" + TRACE_ID + "-0123456789abcdef-01", Instant.now().plusSeconds(60).toString(),
                module, ecosystem, maxDependencies, cursor);
    }

    private String createRepository(String taskId, String content) throws Exception {
        Path repository = root.resolve(taskId);
        Files.createDirectories(repository.resolve("src"));
        Files.writeString(repository.resolve("src/Application.java"), content);
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "test@example.local");
        run(repository, "git", "config", "user.name", "Test");
        run(repository, "git", "add", ".");
        run(repository, "git", "commit", "-qm", "initial");
        return output(repository, "git", "rev-parse", "HEAD");
    }

    private static String output(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).strip();
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
