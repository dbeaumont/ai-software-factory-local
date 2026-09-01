package com.example.aifactory.context.service;

import com.example.aifactory.context.model.ContextModels.GetSymbolsRequest;
import com.example.aifactory.context.model.ContextModels.GetSymbolsResult;
import com.example.aifactory.context.model.ContextModels.ParserDescriptor;
import com.example.aifactory.context.model.ContextModels.RequestContext;
import com.example.aifactory.context.model.ContextModels.Symbol;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterGo;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterKotlin;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterTypescript;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Tree-sitter-backed symbol index. The bean and its MCP callback provider exist only when the feature flag is enabled. */
public final class RepositorySymbolTools {
    static final String PARSER_NAME = "tree-sitter-ng";
    static final String PARSER_VERSION = "0.26.6+grammars.20260301";
    private static final ParserDescriptor PARSER = new ParserDescriptor(PARSER_NAME, PARSER_VERSION);
    private static final int MAX_INDEX_FILES = 1_000;
    private static final int MAX_INDEX_SYMBOLS = 5_000;
    private static final int MAX_SOURCE_BYTES = 1_048_576;
    private static final Duration INDEX_TTL = Duration.ofMinutes(30);
    private static final Duration CURSOR_TTL = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of(
            "java", "kotlin", "javascript", "typescript", "tsx", "python", "go");
    private static final Map<String, String> LANGUAGE_ALIASES = Map.of(
            "js", "javascript", "ts", "typescript", "py", "python", "golang", "go");

    private final RepositoryContextTools contextTools;
    private final Map<IndexKey, SymbolIndex> indexes = new ConcurrentHashMap<>();
    private final Map<String, SymbolCursor> cursors = new ConcurrentHashMap<>();

    public RepositorySymbolTools(RepositoryContextTools contextTools) {
        this.contextTools = contextTools;
    }

    @Tool(name = "context.get_symbols", description = "Return a bounded tree-sitter symbol index pinned to a repository commit and parser version")
    public GetSymbolsResult getSymbolsTool(
            @ToolParam(description = "Contract schema version, currently 1") String schema_version,
            @ToolParam(description = "Registered AI Factory task identifier") String task_id,
            @ToolParam(description = "Stable workflow attempt identifier") String attempt_id,
            @ToolParam(description = "Immutable 40-character source commit SHA") String source_commit,
            @ToolParam(description = "Authorized caller role") String actor,
            @ToolParam(description = "32-character distributed trace identifier") String trace_id,
            @ToolParam(description = "W3C trace context") String traceparent,
            @ToolParam(description = "RFC 3339 call deadline") String deadline,
            @ToolParam(required = false, description = "Repository-relative file or directory path; mutually exclusive with query") String path,
            @ToolParam(required = false, description = "Case-insensitive symbol name fragment; mutually exclusive with path") String query,
            @ToolParam(required = false, description = "Optional language filter") String language,
            @ToolParam(required = false, description = "Maximum symbols returned on this page") Integer max_results,
            @ToolParam(required = false, description = "Opaque continuation cursor") String cursor) throws Exception {
        return getSymbols(new GetSymbolsRequest(schema_version, task_id, attempt_id, source_commit, actor,
                trace_id, traceparent, deadline, path, query, language, max_results, cursor));
    }

    public GetSymbolsResult getSymbols(GetSymbolsRequest request) throws Exception {
        validateSelection(request.path(), request.query());
        String language = normalizeLanguage(request.language());
        Path workspace = contextTools.symbolWorkspace(request.context());
        prune();
        int pageSize = bounded(request.maxResults(), 100, 1, 500, "maxResults");
        if (request.cursor() != null && !request.cursor().isBlank()) {
            return continuePage(request, language);
        }

        String selectedPath = null;
        if (request.path() != null) {
            selectedPath = RepositoryContextTools.symbolRelativePath(
                    workspace, contextTools.symbolSelection(workspace, request.path()));
        }
        IndexKey key = new IndexKey(request.taskId(), request.sourceCommit(), PARSER_NAME, PARSER_VERSION);
        SymbolIndex index = indexFor(key, workspace, request.context());
        List<Symbol> selected = select(index.symbols(), selectedPath, request.query(), language);
        return page(request.taskId(), request.sourceCommit(), selectedPath, request.query(), language,
                selected, 0, pageSize, index.hardTruncated());
    }

    synchronized int cachedIndexCount() {
        prune();
        return indexes.size();
    }

    private synchronized SymbolIndex indexFor(IndexKey key, Path workspace, RequestContext context) throws Exception {
        SymbolIndex cached = indexes.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached;
        }
        List<Path> candidates = contextTools.symbolSourceFiles(workspace, MAX_INDEX_FILES + 1);
        boolean truncated = candidates.size() > MAX_INDEX_FILES;
        List<Symbol> symbols = new ArrayList<>();
        for (Path file : candidates.subList(0, Math.min(candidates.size(), MAX_INDEX_FILES))) {
            RepositoryContextTools.ensureSymbolDeadline(context);
            LanguageSelection selection = languageFor(file);
            if (selection == null || Files.size(file) > MAX_SOURCE_BYTES) {
                continue;
            }
            parseFile(workspace, file, selection, symbols, context);
            if (symbols.size() >= MAX_INDEX_SYMBOLS) {
                truncated = true;
                break;
            }
        }
        List<Symbol> immutable = symbols.stream()
                .limit(MAX_INDEX_SYMBOLS)
                .sorted(Comparator.comparing(Symbol::path)
                        .thenComparingInt(Symbol::startLine)
                        .thenComparing(Symbol::name))
                .toList();
        SymbolIndex built = new SymbolIndex(immutable, truncated, Instant.now().plus(INDEX_TTL));
        if (indexes.size() >= 64) {
            indexes.entrySet().stream().min(Comparator.comparing(entry -> entry.getValue().expiresAt()))
                    .ifPresent(entry -> indexes.remove(entry.getKey(), entry.getValue()));
        }
        indexes.put(key, built);
        return built;
    }

    private void parseFile(Path workspace, Path file, LanguageSelection selection, List<Symbol> result,
                           RequestContext context) throws Exception {
        byte[] raw = Files.readAllBytes(file);
        String source = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString();
        if (source.indexOf('\0') >= 0) {
            return;
        }
        try (TSLanguage language = selection.languageFactory().get(); TSParser parser = new TSParser()) {
            if (!parser.setLanguage(language)) {
                throw new IllegalStateException("incompatible tree-sitter grammar for " + selection.name());
            }
            try (TSTree tree = parser.parseString(null, source)) {
                Deque<TSNode> pending = new ArrayDeque<>();
                pending.push(tree.getRootNode());
                while (!pending.isEmpty() && result.size() < MAX_INDEX_SYMBOLS) {
                    RepositoryContextTools.ensureSymbolDeadline(context);
                    TSNode node = pending.pop();
                    String kind = symbolKind(node, selection.name(), raw);
                    if (kind != null) {
                        String name = symbolName(node, raw);
                        if (name != null && !name.isBlank() && name.length() <= 512) {
                            result.add(new Symbol(name, kind,
                                    RepositoryContextTools.symbolRelativePath(workspace, file),
                                    node.getStartPoint().getRow() + 1,
                                    Math.max(node.getStartPoint().getRow() + 1, node.getEndPoint().getRow() + 1),
                                    selection.name(), signature(node, raw)));
                        }
                    }
                    for (int index = node.getNamedChildCount() - 1; index >= 0; index--) {
                        pending.push(node.getNamedChild(index));
                    }
                }
            }
        }
    }

    private GetSymbolsResult continuePage(GetSymbolsRequest request, String language) {
        SymbolCursor state = cursors.remove(request.cursor());
        if (state == null || state.expiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("unknown or expired symbol cursor");
        }
        String requestedPath = request.path() == null ? null : normalizeRelativeSelection(request.path());
        if (!state.taskId().equals(request.taskId()) || !state.sourceCommit().equals(request.sourceCommit())
                || !java.util.Objects.equals(state.path(), requestedPath)
                || !java.util.Objects.equals(state.query(), request.query())
                || !java.util.Objects.equals(state.language(), language)) {
            throw new SecurityException("symbol cursor does not belong to this task, commit and selection");
        }
        return page(state.taskId(), state.sourceCommit(), state.path(), state.query(), state.language(),
                state.symbols(), state.offset(), state.pageSize(), state.hardTruncated());
    }

    private GetSymbolsResult page(String taskId, String sourceCommit, String path, String query, String language,
                                  List<Symbol> symbols, int offset, int pageSize, boolean hardTruncated) {
        int end = Math.min(symbols.size(), offset + pageSize);
        String nextCursor = null;
        if (end < symbols.size()) {
            nextCursor = randomCursor();
            cursors.put(nextCursor, new SymbolCursor(taskId, sourceCommit, path, query, language, symbols,
                    end, pageSize, hardTruncated, Instant.now().plus(CURSOR_TTL)));
        }
        return new GetSymbolsResult(sourceCommit, PARSER, List.copyOf(symbols.subList(offset, end)),
                hardTruncated || nextCursor != null, nextCursor);
    }

    private static List<Symbol> select(List<Symbol> symbols, String path, String query, String language) {
        String lowerQuery = query == null ? null : query.toLowerCase(Locale.ROOT);
        return symbols.stream()
                .filter(symbol -> language == null || symbol.language().equals(language))
                .filter(symbol -> path == null || symbol.path().equals(path) || symbol.path().startsWith(path + '/'))
                .filter(symbol -> lowerQuery == null || symbol.name().toLowerCase(Locale.ROOT).contains(lowerQuery))
                .toList();
    }

    private static LanguageSelection languageFor(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) return new LanguageSelection("java", TreeSitterJava::new);
        if (name.endsWith(".kt") || name.endsWith(".kts")) return new LanguageSelection("kotlin", TreeSitterKotlin::new);
        if (name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs")) return new LanguageSelection("javascript", TreeSitterJavascript::new);
        if (name.endsWith(".tsx")) return new LanguageSelection("tsx", TreeSitterTypescript::new);
        if (name.endsWith(".ts")) return new LanguageSelection("typescript", TreeSitterTypescript::new);
        if (name.endsWith(".py")) return new LanguageSelection("python", TreeSitterPython::new);
        if (name.endsWith(".go")) return new LanguageSelection("go", TreeSitterGo::new);
        return null;
    }

    private static String symbolKind(TSNode node, String language, byte[] source) {
        String type = node.getType();
        return switch (type) {
            case "package_declaration", "namespace_definition" -> "NAMESPACE";
            case "module_declaration" -> "MODULE";
            case "class_declaration", "class_definition" -> "CLASS";
            case "interface_declaration", "annotation_type_declaration" -> "INTERFACE";
            case "enum_declaration" -> "ENUM";
            case "record_declaration" -> "RECORD";
            case "function_declaration", "function_definition", "function_item" -> "FUNCTION";
            case "method_declaration", "method_definition" -> "METHOD";
            case "constructor_declaration", "secondary_constructor" -> "CONSTRUCTOR";
            case "property_declaration" -> "PROPERTY";
            case "type_alias", "type_alias_declaration", "type_spec" -> "TYPE";
            case "const_declaration" -> "CONSTANT";
            case "variable_declarator" -> variableKind(node, source);
            default -> kotlinDeclaration(type, language);
        };
    }

    private static String variableKind(TSNode node, byte[] source) {
        for (TSNode parent = node.getParent(); parent != null && !parent.isNull(); parent = parent.getParent()) {
            if (parent.getType().equals("field_declaration")) return "FIELD";
            if (Set.of("method_declaration", "constructor_declaration", "function_declaration",
                    "function_definition", "method_definition").contains(parent.getType())) return null;
            if (Set.of("program", "module").contains(parent.getType())) {
                TSNode declaration = node.getParent();
                return declaration != null && nodeText(declaration, source).stripLeading().startsWith("const ")
                        ? "CONSTANT" : "PROPERTY";
            }
        }
        return null;
    }

    private static String kotlinDeclaration(String type, String language) {
        if (!language.equals("kotlin")) return null;
        return switch (type) {
            case "function_declaration" -> "FUNCTION";
            case "object_declaration", "companion_object" -> "CLASS";
            default -> null;
        };
    }

    private static String symbolName(TSNode node, byte[] source) {
        TSNode named = node.getChildByFieldName("name");
        if (usable(named)) {
            return compact(nodeText(named, source), 512);
        }
        Deque<TSNode> pending = new ArrayDeque<>();
        for (int index = 0; index < node.getNamedChildCount(); index++) {
            pending.addLast(node.getNamedChild(index));
        }
        while (!pending.isEmpty()) {
            TSNode candidate = pending.removeFirst();
            if (Set.of("identifier", "type_identifier", "property_identifier", "field_identifier")
                    .contains(candidate.getType())) {
                return compact(nodeText(candidate, source), 512);
            }
            if (candidate.getStartPoint().getRow() == node.getStartPoint().getRow()) {
                for (int index = 0; index < candidate.getNamedChildCount(); index++) {
                    pending.addLast(candidate.getNamedChild(index));
                }
            }
        }
        return null;
    }

    private static boolean usable(TSNode node) {
        return node != null && !node.isNull();
    }

    private static String signature(TSNode node, byte[] source) {
        String content = nodeText(node, source);
        int newline = content.indexOf('\n');
        int brace = content.indexOf('{');
        int cut = content.length();
        if (newline >= 0) cut = Math.min(cut, newline);
        if (brace >= 0) cut = Math.min(cut, brace);
        String header = content.substring(0, cut).strip()
                .replaceAll("\\\"(?:\\\\.|[^\\\"\\\\])*\\\"", "\"[redacted]\"")
                .replaceAll("'(?:\\\\.|[^'\\\\])*'", "'[redacted]'");
        return header.isEmpty() ? null : compact(header, 2_048);
    }

    private static String nodeText(TSNode node, byte[] source) {
        int start = Math.max(0, Math.min(node.getStartByte(), source.length));
        int end = Math.max(start, Math.min(node.getEndByte(), source.length));
        return new String(source, start, end - start, StandardCharsets.UTF_8);
    }

    private static String compact(String value, int maximum) {
        String compact = value.replaceAll("\\s+", " ").strip();
        return compact.length() <= maximum ? compact : compact.substring(0, maximum - 3) + "...";
    }

    private static void validateSelection(String path, String query) {
        boolean hasPath = path != null && !path.isBlank();
        boolean hasQuery = query != null && !query.isBlank();
        if (hasPath == hasQuery) {
            throw new IllegalArgumentException("exactly one of path or query is required");
        }
        if (hasPath && path.length() > 1_024 || hasQuery && query.length() > 256) {
            throw new IllegalArgumentException("symbol selection exceeds contract limits");
        }
    }

    private static String normalizeLanguage(String requested) {
        if (requested == null || requested.isBlank()) return null;
        String normalized = requested.toLowerCase(Locale.ROOT);
        normalized = LANGUAGE_ALIASES.getOrDefault(normalized, normalized);
        if (!SUPPORTED_LANGUAGES.contains(normalized)) {
            throw new IllegalArgumentException("unsupported symbol language");
        }
        return normalized;
    }

    private static String normalizeRelativeSelection(String requested) {
        Path path = Path.of(requested).normalize();
        return path.toString().replace('\\', '/');
    }

    private static int bounded(Integer value, int defaultValue, int minimum, int maximum, String name) {
        int result = value == null ? defaultValue : value;
        if (result < minimum || result > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return result;
    }

    private void prune() {
        Instant now = Instant.now();
        indexes.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        cursors.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private static String randomCursor() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private record LanguageSelection(String name, Supplier<TSLanguage> languageFactory) {
    }

    private record IndexKey(String taskId, String sourceCommit, String parserName, String parserVersion) {
    }

    private record SymbolIndex(List<Symbol> symbols, boolean hardTruncated, Instant expiresAt) {
    }

    private record SymbolCursor(String taskId, String sourceCommit, String path, String query, String language,
                                List<Symbol> symbols, int offset, int pageSize, boolean hardTruncated,
                                Instant expiresAt) {
    }
}
