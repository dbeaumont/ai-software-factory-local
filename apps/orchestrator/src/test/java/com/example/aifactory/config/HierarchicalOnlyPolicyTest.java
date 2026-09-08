package com.example.aifactory.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class HierarchicalOnlyPolicyTest {
    @Test
    void activePoliciesContainNoLegacyExecutionModeOrPipelineFallback() throws Exception {
        String routing = Files.readString(resource("resources/multiagents/policies/routing-policy-v1.yaml"));
        String risk = Files.readString(resource("resources/multiagents/policies/risk-policy-v1.yaml"));
        String rollback = Files.readString(resource("resources/multiagents/policies/rollback-policy-v1.yaml"));

        assertThat(routing).doesNotContain("modeCeilings", "PIPELINE_BASELINE", "HIERARCHICAL_SHADOW",
                "HIERARCHICAL_CANARY", "HIERARCHICAL_ACTIVE");
        assertThat(risk).doesNotContain("modeMatrix", "effective_mode", "HIERARCHICAL_SHADOW",
                "HIERARCHICAL_CANARY", "HIERARCHICAL_ACTIVE");
        assertThat(rollback).contains("safeState: FREEZE_ADMISSIONS", "restoreCompatibleBuildOnly: true")
                .doesNotContain("safeMode", "route newly admitted tasks to PIPELINE", "minimumMode");
    }

    private static Path resource(String relative) {
        Path workingDirectory = Path.of("").toAbsolutePath();
        for (Path candidate : java.util.List.of(
                workingDirectory.resolve(relative), workingDirectory.resolve("../../").resolve(relative).normalize())) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Cannot find " + relative);
    }
}
