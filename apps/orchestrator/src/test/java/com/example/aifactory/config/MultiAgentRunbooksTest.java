package com.example.aifactory.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiAgentRunbooksTest {
    private static final List<String> RUNBOOKS = List.of(
            "SATURATION.md", "AGENT-DEFAILLANT.md", "TEMPORAL-INDISPONIBLE.md",
            "MCP-COMPROMIS.md", "ROLLBACK-MULTI-AGENTS.md");
    private static final List<String> REQUIRED_SECTIONS = List.of(
            "## Confinement immédiat", "## Diagnostic", "## Rétablissement",
            "## Vérification et clôture", "## Escalade");

    @Test
    void providesFiveActionableOperationalRunbooks() throws Exception {
        Path root = repositoryRoot();
        Path directory = root.resolve("docs/runbooks");
        for (String name : RUNBOOKS) {
            String runbook = Files.readString(directory.resolve(name));
            assertThat(runbook).as(name).contains(REQUIRED_SECTIONS.toArray(String[]::new));
        }

        String alerts = Files.readString(
                root.resolve("infrastructure/observability/alerts/multiagents.yml"));
        assertThat(alerts).contains("/docs/runbooks/SATURATION.md", "/docs/runbooks/AGENT-DEFAILLANT.md",
                "/docs/runbooks/MCP-COMPROMIS.md");
    }

    private static Path repositoryRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.isDirectory(cwd.resolve("docs/runbooks"))) return cwd;
        return cwd.resolve("../..").normalize();
    }
}
