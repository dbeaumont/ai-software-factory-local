package com.example.aifactory.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MultiAgentUiContractTest {
    private static final Path WEB = Path.of(System.getProperty("user.dir")).resolve("../../apps/web").normalize();

    @Test
    void rendersTheParentChildDelegationDagWithoutRemovingThePipelineStepper() throws Exception {
        String html = Files.readString(WEB.resolve("index.html"));
        String javascript = Files.readString(WEB.resolve("app.js"));
        String css = Files.readString(WEB.resolve("pipeline.css"));

        assertThat(html).contains("id=\"steps\"", "id=\"delegation-graph\"", "id=\"delegation-tree\"");
        assertThat(javascript).contains("function renderDelegationDag(task)", "node.parentDelegationId",
                "node.dependsOn", "renderDelegationDag(task)");
        assertThat(css).contains(".delegation-node", ".delegation-children", ".delegation-status");
    }

    @Test
    void distinguishesEverySpecialistPerimeterAndIndependentReview() throws Exception {
        String html = Files.readString(WEB.resolve("index.html"));
        String javascript = Files.readString(WEB.resolve("app.js"));
        String css = Files.readString(WEB.resolve("pipeline.css"));

        assertThat(html).contains("Architecture", "Code", "Tests", "Sécurité", "Revue indépendante");
        assertThat(javascript).contains("function delegationPerimeter(role)", "perimeter-${perimeter.id}",
                "delegation-perimeter");
        assertThat(css).contains(".perimeter-architecture", ".perimeter-code", ".perimeter-tests",
                ".perimeter-security", ".perimeter-review");
    }

    @Test
    void displaysDelegationDurationTurnsTokensCostAndTools() throws Exception {
        String javascript = Files.readString(WEB.resolve("app.js"));
        String css = Files.readString(WEB.resolve("pipeline.css"));

        assertThat(javascript).contains("node.durationMillis", "node.turns", "node.tokens", "node.costMicros",
                "node.toolsUsed", "function formatDuration(durationMillis)");
        assertThat(css).contains(".delegation-metrics", ".delegation-tools");
    }
}
