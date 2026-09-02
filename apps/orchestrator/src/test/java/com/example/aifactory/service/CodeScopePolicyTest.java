package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeScopePolicyTest {
    private final CodeScopePolicy policy = new CodeScopePolicy();

    @Test
    void parsesTypedFileDirectoryAndModuleRulesFromTheCodeContract() throws Exception {
        Path fixture = Path.of(System.getProperty("user.dir"))
                .resolve("../../resources/multiagents/fixtures/golden-contracts-v1.json").normalize();
        var codeTask = new ObjectMapper().readTree(Files.readString(fixture))
                .path("documents").path("code-task-v1");

        CodeScopePolicy.Scope scope = policy.fromCodeTask(codeTask);

        assertThat(scope.repositoryId()).isEqualTo("customer-api");
        assertThat(scope.rules()).containsExactlyInAnyOrder(
                rule(CodeScopePolicy.Kind.MODULE, "src"),
                rule(CodeScopePolicy.Kind.FILE, "src/App.java"));
    }

    @Test
    void classifiesDisjointContainmentIdentityAndPartialOverlapConservatively() {
        assertThat(policy.classify(scope(rule(CodeScopePolicy.Kind.FILE, "src/A.java")),
                scope(rule(CodeScopePolicy.Kind.FILE, "src/B.java")))).isEqualTo(CodeScopePolicy.Relation.DISJOINT);
        assertThat(policy.classify(scope(rule(CodeScopePolicy.Kind.DIRECTORY, "src")),
                scope(rule(CodeScopePolicy.Kind.FILE, "src/A.java"))))
                .isEqualTo(CodeScopePolicy.Relation.CONTAINS);
        assertThat(policy.classify(scope(rule(CodeScopePolicy.Kind.MODULE, "module-a")),
                scope(rule(CodeScopePolicy.Kind.MODULE, "module-a"))))
                .isEqualTo(CodeScopePolicy.Relation.IDENTICAL);
        assertThat(policy.classify(scope(
                        rule(CodeScopePolicy.Kind.DIRECTORY, "src/a"),
                        rule(CodeScopePolicy.Kind.DIRECTORY, "src/b")),
                scope(rule(CodeScopePolicy.Kind.DIRECTORY, "src/a/internal"),
                        rule(CodeScopePolicy.Kind.DIRECTORY, "src/c"))))
                .isEqualTo(CodeScopePolicy.Relation.OVERLAPPING);
    }

    @Test
    void rejectsAmbiguousOrEscapingPathsAndScopesWithoutWriteRules() {
        for (String invalid : java.util.List.of("/absolute", "../escape", "src/../other", "src\\file", "src//x")) {
            assertThatThrownBy(() -> rule(CodeScopePolicy.Kind.FILE, invalid))
                    .as(invalid).hasMessageContaining("not canonical");
        }
        assertThatThrownBy(() -> new CodeScopePolicy.Scope("repo", Set.of(
                new CodeScopePolicy.Rule(CodeScopePolicy.Kind.DIRECTORY,
                        CodeScopePolicy.Access.READ, "src"))))
                .hasMessageContaining("write rule");
    }

    @Test
    void authorizesParallelismOnlyWhenEveryWriteScopePairIsProvablyDisjoint() {
        CodeScopePolicy.ParallelismProof proof = policy.requireParallelizable(java.util.List.of(
                scoped("developer-b", scope(rule(CodeScopePolicy.Kind.FILE, "src/B.java"))),
                scoped("developer-a", scope(rule(CodeScopePolicy.Kind.FILE, "src/A.java")))));

        assertThat(proof.nodeIds()).containsExactly("developer-a", "developer-b");
        assertThat(proof.reason()).isEqualTo("PAIRWISE_DISJOINT_WRITE_SCOPES");
        assertThatThrownBy(() -> policy.requireParallelizable(java.util.List.of(
                scoped("developer-a", scope(rule(CodeScopePolicy.Kind.DIRECTORY, "src"))),
                scoped("developer-b", scope(rule(CodeScopePolicy.Kind.FILE, "src/B.java"))))))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not provably disjoint")
                .hasMessageContaining("CONTAINS");
        assertThatThrownBy(() -> policy.requireParallelizable(java.util.List.of(
                scoped("developer-a", scope(rule(CodeScopePolicy.Kind.MODULE, "module-a"))),
                scoped("developer-b", scope(rule(CodeScopePolicy.Kind.MODULE, "module-a"))))))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("IDENTICAL");
    }

    private static CodeScopePolicy.Scope scope(CodeScopePolicy.Rule... rules) {
        return new CodeScopePolicy.Scope("customer-api", Set.of(rules));
    }

    private static CodeScopePolicy.Rule rule(CodeScopePolicy.Kind kind, String path) {
        return new CodeScopePolicy.Rule(kind, CodeScopePolicy.Access.WRITE, path);
    }

    private static CodeScopePolicy.ScopedDelegation scoped(String nodeId, CodeScopePolicy.Scope scope) {
        return new CodeScopePolicy.ScopedDelegation(nodeId, scope);
    }
}
