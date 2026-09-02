package com.example.aifactory.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Canonical Code scope model and conservative overlap classifier. */
@Component
public final class CodeScopePolicy {
    public Scope fromCodeTask(JsonNode codeTask) {
        JsonNode scope = codeTask.path("scope");
        Set<Rule> rules = new LinkedHashSet<>();
        for (JsonNode rule : scope.path("rules")) {
            rules.add(new Rule(Kind.valueOf(rule.path("kind").asText()),
                    Access.valueOf(rule.path("access").asText()), rule.path("path").asText()));
        }
        return new Scope(scope.path("repository_id").asText(), rules);
    }

    public Relation classify(Scope left, Scope right) {
        if (!left.repositoryId().equals(right.repositoryId())) return Relation.DISJOINT;
        List<Rule> leftWrites = writes(left);
        List<Rule> rightWrites = writes(right);
        boolean leftContains = containsAll(leftWrites, rightWrites);
        boolean rightContains = containsAll(rightWrites, leftWrites);
        if (leftContains && rightContains) return Relation.IDENTICAL;
        if (leftContains) return Relation.CONTAINS;
        if (rightContains) return Relation.CONTAINED_BY;
        boolean overlaps = leftWrites.stream().anyMatch(leftRule -> rightWrites.stream()
                .anyMatch(rightRule -> overlap(leftRule, rightRule)));
        return overlaps ? Relation.OVERLAPPING : Relation.DISJOINT;
    }

    private static List<Rule> writes(Scope scope) {
        return scope.rules().stream().filter(rule -> rule.access() == Access.WRITE)
                .sorted(Comparator.comparing(Rule::path).thenComparing(rule -> rule.kind().name())).toList();
    }

    private static boolean containsAll(List<Rule> candidate, List<Rule> target) {
        return !target.isEmpty() && target.stream().allMatch(targetRule -> candidate.stream()
                .anyMatch(candidateRule -> contains(candidateRule, targetRule)));
    }

    private static boolean overlap(Rule left, Rule right) {
        return contains(left, right) || contains(right, left) || left.path().equals(right.path());
    }

    private static boolean contains(Rule container, Rule nested) {
        if (container.kind() == Kind.FILE) {
            return nested.kind() == Kind.FILE && container.path().equals(nested.path());
        }
        return nested.path().equals(container.path()) || nested.path().startsWith(container.path() + '/');
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.endsWith("/")
                || value.contains("\\") || value.contains("//")) {
            throw invalid("scope path is not canonical: " + value);
        }
        Path path = Path.of(value).normalize();
        String normalized = path.toString();
        if (!normalized.equals(value) || normalized.equals(".") || normalized.startsWith("../")
                || normalized.contains("/../") || normalized.contains("/./")) {
            throw invalid("scope path is not canonical: " + value);
        }
        return normalized;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid Code scope: " + reason);
    }

    public record Scope(String repositoryId, Set<Rule> rules) {
        public Scope {
            if (repositoryId == null || !repositoryId.matches("[a-z0-9][a-z0-9-]{1,62}")) {
                throw invalid("repository id is invalid");
            }
            rules = rules == null ? Set.of() : Set.copyOf(rules);
            if (rules.isEmpty() || rules.size() > 128
                    || rules.stream().noneMatch(rule -> rule.access() == Access.WRITE)) {
                throw invalid("at least one bounded write rule is required");
            }
        }
    }

    public record Rule(Kind kind, Access access, String path) {
        public Rule {
            if (kind == null || access == null) throw invalid("scope rule type and access are required");
            path = normalize(path);
        }
    }

    public enum Kind { FILE, DIRECTORY, MODULE }
    public enum Access { READ, WRITE }
    public enum Relation { DISJOINT, IDENTICAL, CONTAINS, CONTAINED_BY, OVERLAPPING }
}
