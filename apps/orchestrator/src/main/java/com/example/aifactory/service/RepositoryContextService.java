package com.example.aifactory.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class RepositoryContextService implements RepositoryContextProvider {
    private static final Set<String> EXT = Set.of(".java", ".kt", ".xml", ".yml", ".yaml", ".json", ".ts", ".js", ".properties", ".gradle");
    private static final Pattern SENSITIVE_PATH = Pattern.compile("(?i)(^|/)(\\.env|\\.vault|.*(?:secret|credential|password|token|private.?key|id_rsa).*)($|/)");
    private static final Pattern SENSITIVE_SETTING = Pattern.compile("(?im)^([ \\t]*[A-Za-z0-9_.-]*(?:password|secret|token|api[_-]?key|private[_-]?key)[A-Za-z0-9_.-]*[ \\t]*[=:][ \\t]*).*$");

    public String collect(Path repo) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(repo)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/.git/"))
                    .filter(this::accepted)
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(80)
                    .forEach(files::add);
        }
        StringBuilder sb = new StringBuilder();
        int max = 40_000;
        for (Path p : files) {
            if (sb.length() >= max) break;
            String rel = repo.relativize(p).toString();
            String content;
            try { content = Files.readString(p); } catch (Exception e) { continue; }
            content = redactSensitiveSettings(content);
            if (content.length() > 6_000) content = content.substring(0, 6_000) + "\n...[truncated]";
            sb.append("\n--- FILE: ").append(rel).append(" ---\n").append(content).append('\n');
        }
        return sb.substring(0, Math.min(sb.length(), max));
    }

    @Override
    public String collect(Path repository, String taskId, String sourceCommit) throws IOException {
        return collect(repository);
    }

    private boolean accepted(Path p) {
        String normalized = p.toString().replace('\\', '/');
        if (SENSITIVE_PATH.matcher(normalized).find()) return false;
        String n = p.getFileName().toString();
        if (n.equals("pom.xml") || n.equals("Dockerfile") || n.equals("Makefile")) return true;
        return EXT.stream().anyMatch(n::endsWith);
    }

    private static String redactSensitiveSettings(String content) {
        return SENSITIVE_SETTING.matcher(content).replaceAll("$1[REDACTED]");
    }
}
