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
}
