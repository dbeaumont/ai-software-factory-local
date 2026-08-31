package com.example.aifactory.context.service;

import com.example.aifactory.context.config.RepositoryContextProperties;
import com.example.aifactory.context.model.ContextModels.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@Service
public class RepositoryContextTools {
    private static final Pattern TASK_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SENSITIVE_PATH = Pattern.compile(
            "(?i)(^|/)(\\.env(?:\\..*)?|\\.vault|.*(?:secret|credential|password|token|private.?key|id_rsa).*)($|/)");
    private static final Pattern SENSITIVE_SETTING = Pattern.compile(
            "(?im)^([ \\t]*[A-Za-z0-9_.-]*(?:password|secret|token|api[_-]?key|private[_-]?key)[A-Za-z0-9_.-]*[ \\t]*[=:][ \\t]*).*$");
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", "target", "build", "dist", "node_modules", ".gradle", ".idea", ".vscode", "vendor");
    private static final Set<String> RULE_NAMES = Set.of(
            "AGENTS.md", "CONTRIBUTING.md", "CONTRIBUTING", "README.md", "README", "CODEOWNERS");
    private static final Set<String> CONTEXT_READERS = Set.of("workflow", "planner", "developer");
    private static final Set<String> CODE_SEARCHERS = Set.of("workflow", "planner", "developer", "tester", "reviewer");
    private static final Set<String> FILE_READERS = Set.of(
            "workflow", "planner", "developer", "patch-repair", "tester", "reviewer");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".java", ".kt", ".xml", ".yml", ".yaml", ".json", ".ts", ".js", ".css", ".html",
            ".properties", ".gradle", ".md", ".txt", ".toml", ".sh", ".sql", ".py", ".go");

    private final RepositoryContextProperties properties;

    public RepositoryContextTools(RepositoryContextProperties properties) {
        this.properties = properties;
    }

    @Tool(name = "context.list_tree", description = "List a bounded repository tree for a registered task and immutable source commit")
    public ListTreeResult listTreeTool(
            @ToolParam(description = "Contract schema version, currently 1") String schema_version,
            @ToolParam(description = "Registered AI Factory task identifier") String task_id,
            @ToolParam(description = "Immutable 40-character source commit SHA") String source_commit,
            @ToolParam(description = "Authorized caller role") String actor,
            @ToolParam(description = "32-character distributed trace identifier") String trace_id,
            @ToolParam(required = false, description = "Repository-relative start path") String path,
            @ToolParam(required = false, description = "Maximum traversal depth") Integer depth,
            @ToolParam(required = false, description = "Maximum returned entries") Integer max_entries) throws Exception {
        return listTree(new ListTreeRequest(schema_version, task_id, source_commit, actor, trace_id, path, depth, max_entries));
    }

    public ListTreeResult listTree(ListTreeRequest request) throws Exception {
        authorize(request.actor(), CONTEXT_READERS, "context.list_tree");
        Path workspace = workspace(request.context());
        Path start = resolve(workspace, defaultString(request.path()));
        requireVisibleStart(workspace, start);
        int depth = bounded(request.depth(), 6, 1, 12, "depth");
        int requestedEntries = bounded(request.maxEntries(), 200, 1, properties.maxTreeEntries(), "maxEntries");
        List<Path> paths = collectVisiblePaths(workspace, start, depth, requestedEntries + 1, path -> true);
        List<TreeEntry> entries = new ArrayList<>();
        for (Path path : paths.subList(0, Math.min(paths.size(), requestedEntries))) {
            entries.add(new TreeEntry(normalizedRelative(workspace, path),
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "directory" : "file",
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? Files.size(path) : 0));
        }
        return new ListTreeResult(request.sourceCommit(), List.copyOf(entries), paths.size() > requestedEntries);
    }

    @Tool(name = "context.read_file", description = "Read a bounded line range from an allowed repository text file")
    public ReadFileResult readFileTool(
            @ToolParam(description = "Contract schema version, currently 1") String schema_version,
            @ToolParam(description = "Registered AI Factory task identifier") String task_id,
            @ToolParam(description = "Immutable 40-character source commit SHA") String source_commit,
            @ToolParam(description = "Authorized caller role") String actor,
            @ToolParam(description = "32-character distributed trace identifier") String trace_id,
            @ToolParam(description = "Repository-relative text file path") String path,
            @ToolParam(required = false, description = "First line, one-based") Integer start_line,
            @ToolParam(required = false, description = "Last line, one-based and inclusive") Integer end_line,
            @ToolParam(required = false, description = "Maximum UTF-8 response bytes") Integer max_bytes) throws Exception {
        return readFile(new ReadFileRequest(schema_version, task_id, source_commit, actor, trace_id, path, start_line, end_line, max_bytes));
    }

    public ReadFileResult readFile(ReadFileRequest request) throws Exception {
        authorize(request.actor(), FILE_READERS, "context.read_file");
        Path workspace = workspace(request.context());
        return read(workspace, request.path(), request.startLine(), request.endLine(), request.maxBytes());
    }

    @Tool(name = "context.search_code", description = "Search for a literal string in allowed repository text files and return cited matches")
    public SearchCodeResult searchCodeTool(
            @ToolParam(description = "Contract schema version, currently 1") String schema_version,
            @ToolParam(description = "Registered AI Factory task identifier") String task_id,
            @ToolParam(description = "Immutable 40-character source commit SHA") String source_commit,
            @ToolParam(description = "Authorized caller role") String actor,
            @ToolParam(description = "32-character distributed trace identifier") String trace_id,
            @ToolParam(description = "Literal string to search") String query,
            @ToolParam(required = false, description = "Repository-relative start path") String path,
            @ToolParam(required = false, description = "Maximum returned matches") Integer max_results) throws Exception {
        return searchCode(new SearchCodeRequest(schema_version, task_id, source_commit, actor, trace_id, query, path, max_results));
    }

    public SearchCodeResult searchCode(SearchCodeRequest request) throws Exception {
        authorize(request.actor(), CODE_SEARCHERS, "context.search_code");
        Path workspace = workspace(request.context());
        if (request.query() == null || request.query().isBlank() || request.query().length() > 256) {
            throw new IllegalArgumentException("query must contain between 1 and 256 characters");
        }
        Path start = resolve(workspace, defaultString(request.path()));
        requireVisibleStart(workspace, start);
        int maxResults = bounded(request.maxResults(), 50, 1, 200, "maxResults");
        List<SearchMatch> matches = new ArrayList<>();
        List<Path> files = Files.isRegularFile(start, LinkOption.NOFOLLOW_LINKS)
                ? (isAllowedTextFile(workspace, start) ? List.of(start) : List.of())
                : collectVisiblePaths(workspace, start, 64, properties.maxSearchFiles() + 1,
                    path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && isAllowedTextFile(workspace, path));
        boolean truncated = files.size() > properties.maxSearchFiles();
        for (Path file : files.subList(0, Math.min(files.size(), properties.maxSearchFiles()))) {
            if (Files.size(file) > properties.maxFileBytes()) {
                continue;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).contains(request.query())) {
                    matches.add(new SearchMatch(normalizedRelative(workspace, file), index + 1,
                            abbreviate(redact(lines.get(index).strip()), 400)));
                    if (matches.size() >= maxResults) {
                        return new SearchCodeResult(request.sourceCommit(), List.copyOf(matches), true);
                    }
                }
            }
        }
        return new SearchCodeResult(request.sourceCommit(), List.copyOf(matches), truncated);
    }

    @Tool(name = "context.get_repository_rules", description = "Read bounded repository guidance files as untrusted contextual evidence")
    public RepositoryRulesResult getRepositoryRulesTool(
            @ToolParam(description = "Contract schema version, currently 1") String schema_version,
            @ToolParam(description = "Registered AI Factory task identifier") String task_id,
            @ToolParam(description = "Immutable 40-character source commit SHA") String source_commit,
            @ToolParam(description = "Authorized caller role") String actor,
            @ToolParam(description = "32-character distributed trace identifier") String trace_id) throws Exception {
        return getRepositoryRules(new RepositoryRulesRequest(schema_version, task_id, source_commit, actor, trace_id));
    }

    public RepositoryRulesResult getRepositoryRules(RepositoryRulesRequest request) throws Exception {
        authorize(request.actor(), CONTEXT_READERS, "context.get_repository_rules");
        Path workspace = workspace(request.context());
        List<ReadFileResult> rules = new ArrayList<>();
        List<Path> paths = collectVisiblePaths(workspace, workspace, 8, 20,
                path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        && RULE_NAMES.contains(path.getFileName().toString()));
        for (Path file : paths) {
            rules.add(read(workspace, normalizedRelative(workspace, file), 1, null, 16_384));
        }
        return new RepositoryRulesResult(request.sourceCommit(), List.copyOf(rules));
    }

    private ReadFileResult read(Path workspace, String requestedPath, Integer requestedStart, Integer requestedEnd,
                                Integer requestedMaxBytes) throws Exception {
        Path file = resolve(workspace, requestedPath);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !isAllowedTextFile(workspace, file)) {
            throw new IllegalArgumentException("path is not an allowed text file");
        }
        if (Files.size(file) > properties.maxFileBytes()) {
            throw new IllegalArgumentException("file exceeds the configured size limit");
        }
        int startLine = bounded(requestedStart, 1, 1, Integer.MAX_VALUE, "startLine");
        int endLine = requestedEnd == null ? Integer.MAX_VALUE : bounded(requestedEnd, startLine, startLine, Integer.MAX_VALUE, "endLine");
        int maxBytes = bounded(requestedMaxBytes, 16_384, 1, Math.min(65_536, properties.maxFileBytes()), "maxBytes");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        StringBuilder content = new StringBuilder();
        int actualEnd = startLine - 1;
        boolean truncated = false;
        for (int line = startLine; line <= lines.size() && line <= endLine; line++) {
            String candidate = redact(lines.get(line - 1)) + "\n";
            if (content.toString().getBytes(StandardCharsets.UTF_8).length + candidate.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                truncated = true;
                break;
            }
            content.append(candidate);
            actualEnd = line;
        }
        if (actualEnd < Math.min(lines.size(), endLine)) {
            truncated = true;
        }
        return new ReadFileResult(normalizedRelative(workspace, file), startLine, actualEnd,
                content.toString(), sha256(Files.readAllBytes(file)), truncated);
    }

    private Path workspace(RequestContext context) throws Exception {
        validateContext(context);
        Path configuredRoot = properties.workspaceRoot().toAbsolutePath().normalize();
        Path root = configuredRoot.toRealPath();
        Path candidate = root.resolve(context.taskId()).normalize();
        if (!candidate.startsWith(root) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("unknown task workspace");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException("task workspace escapes configured root");
        }
        String actualCommit = resolveGitCommit(real);
        if (!actualCommit.equals(context.sourceCommit())) {
            throw new IllegalArgumentException("source commit does not match task workspace");
        }
        return real;
    }

    private static void validateContext(RequestContext context) {
        if (context == null || !"1".equals(context.schemaVersion())) {
            throw new IllegalArgumentException("unsupported schemaVersion");
        }
        if (context.taskId() == null || !TASK_ID.matcher(context.taskId()).matches()) {
            throw new IllegalArgumentException("invalid taskId");
        }
        if (context.sourceCommit() == null || !COMMIT.matcher(context.sourceCommit()).matches()) {
            throw new IllegalArgumentException("invalid sourceCommit");
        }
        if (context.actor() == null || context.actor().isBlank()) {
            throw new IllegalArgumentException("actor is required");
        }
        if (context.traceId() == null || !TRACE_ID.matcher(context.traceId()).matches()) {
            throw new IllegalArgumentException("invalid traceId");
        }
    }

    private static Path resolve(Path workspace, String requestedPath) throws IOException {
        if (requestedPath == null) {
            throw new IllegalArgumentException("path is required");
        }
        Path relative = Path.of(requestedPath);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("absolute paths are forbidden");
        }
        Path candidate = workspace.resolve(relative).normalize();
        if (!candidate.startsWith(workspace) || !Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("path is outside the task workspace or does not exist");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(workspace)) {
            throw new IllegalArgumentException("symbolic link escapes task workspace");
        }
        return real;
    }

    private static boolean isVisible(Path workspace, Path path) {
        String relative = normalizedRelative(workspace, path);
        if (SENSITIVE_PATH.matcher(relative).find()) {
            return false;
        }
        for (Path segment : workspace.relativize(path)) {
            if (EXCLUDED_DIRECTORIES.contains(segment.toString())) {
                return false;
            }
        }
        if (Files.isSymbolicLink(path)) {
            try {
                return path.toRealPath().startsWith(workspace);
            } catch (IOException ignored) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllowedTextFile(Path workspace, Path path) {
        if (!isVisible(workspace, path)) {
            return false;
        }
        String name = path.getFileName().toString();
        if (name.equals("Dockerfile") || name.equals("Makefile") || RULE_NAMES.contains(name)) {
            return true;
        }
        return TEXT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static void requireVisibleStart(Path workspace, Path start) {
        if (!start.equals(workspace) && !isVisible(workspace, start)) {
            throw new IllegalArgumentException("path is excluded by repository policy");
        }
    }

    private static void authorize(String actor, Set<String> allowedActors, String tool) {
        if (actor == null || !allowedActors.contains(actor)) {
            throw new SecurityException("actor is not authorized for " + tool);
        }
    }

    private static List<Path> collectVisiblePaths(Path workspace, Path start, int maxDepth, int maximum,
                                                  Predicate<Path> include) throws IOException {
        List<Path> result = new ArrayList<>();
        collectVisiblePaths(workspace, start, 0, maxDepth, maximum, include, result);
        return result;
    }

    private static void collectVisiblePaths(Path workspace, Path directory, int currentDepth, int maxDepth,
                                            int maximum, Predicate<Path> include, List<Path> result) throws IOException {
        if (currentDepth >= maxDepth || result.size() >= maximum
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            stream.forEach(children::add);
        }
        children.sort(Comparator.comparing(path -> path.getFileName().toString()));
        for (Path child : children) {
            if (result.size() >= maximum) {
                return;
            }
            if (!isVisible(workspace, child)) {
                continue;
            }
            if (include.test(child)) {
                result.add(child);
            }
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                collectVisiblePaths(workspace, child, currentDepth + 1, maxDepth, maximum, include, result);
            }
        }
    }

    private static String resolveGitCommit(Path workspace) throws Exception {
        Process process = new ProcessBuilder("git", "-c", "safe.directory=" + workspace,
                "-C", workspace.toString(), "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IllegalStateException("git commit lookup timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0 || !COMMIT.matcher(output).matches()) {
            throw new IllegalArgumentException("task workspace is not pinned to a readable git commit");
        }
        return output;
    }

    private static String redact(String content) {
        return SENSITIVE_SETTING.matcher(content).replaceAll("$1[REDACTED]");
    }

    private static int bounded(Integer value, int defaultValue, int minimum, int maximum, String name) {
        int result = value == null ? defaultValue : value;
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return result;
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }

    private static String normalizedRelative(Path workspace, Path path) {
        return workspace.relativize(path).toString().replace('\\', '/');
    }

    private static String abbreviate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum - 3) + "...";
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
