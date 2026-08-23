package com.example.aifactory.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
public class RepositoryContextService {
    private static final Set<String> EXT = Set.of(".java", ".kt", ".xml", ".yml", ".yaml", ".json", ".ts", ".js", ".md", ".properties", ".gradle");

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
        int max = 80_000;
        for (Path p : files) {
            if (sb.length() >= max) break;
            String rel = repo.relativize(p).toString();
            String content;
            try { content = Files.readString(p); } catch (Exception e) { continue; }
            if (content.length() > 8_000) content = content.substring(0, 8_000) + "\n...[truncated]";
            sb.append("\n--- FILE: ").append(rel).append(" ---\n").append(content).append('\n');
        }
        return sb.substring(0, Math.min(sb.length(), max));
    }

    private boolean accepted(Path p) {
        String n = p.getFileName().toString();
        if (n.equals("pom.xml") || n.equals("Dockerfile") || n.equals("Makefile")) return true;
        return EXT.stream().anyMatch(n::endsWith);
    }
}
