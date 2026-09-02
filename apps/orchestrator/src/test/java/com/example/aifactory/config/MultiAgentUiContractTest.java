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

    @Test
    void displaysCodeScopesTouchedFilesAndCollisions() throws Exception {
        String javascript = Files.readString(WEB.resolve("app.js"));
        String css = Files.readString(WEB.resolve("pipeline.css"));

        assertThat(javascript).contains("node.codeImpact.scopes", "node.codeImpact.touchedFiles",
                "node.codeImpact.collisions", "has-collisions");
        assertThat(css).contains(".delegation-code-impact", ".has-collisions");
    }

    @Test
    void displaysEvidenceMetadataWithoutLoadingSensitiveContent() throws Exception {
        String html = Files.readString(WEB.resolve("index.html"));
        String javascript = Files.readString(WEB.resolve("app.js"));
        String css = Files.readString(WEB.resolve("pipeline.css"));

        assertThat(html).contains("id=\"evidence-panel\"", "id=\"evidence-list\"");
        assertThat(javascript).contains("function renderEvidence(task)", "artifact.status",
                "artifact.classification", "artifact.digest", "artifact.uri", "artifact.sizeBytes")
                .doesNotContain("artifact.content");
        assertThat(css).contains(".evidence-item", ".evidence-header");
    }

    @Test
    void presentsContradictionsAndAlternativesBeforeHumanApproval() throws Exception {
        String html = Files.readString(WEB.resolve("index.html"));
        String javascript = Files.readString(WEB.resolve("app.js"));
        String css = Files.readString(WEB.resolve("pipeline.css"));

        assertThat(html).contains("id=\"human-decision-panel\"", "id=\"human-decision-list\"");
        assertThat(javascript).contains("function renderHumanDecisions(task)", "task.contradictions",
                "action.alternatives", "option.consequence", "hasPendingHumanDecision");
        assertThat(css).contains(".human-contradiction", ".decision-alternatives");
    }

    @Test
    void bindsApprovalToTheDisplayedFinalManifestAndRefreshesOnConflict() throws Exception {
        String html = Files.readString(WEB.resolve("index.html"));
        String javascript = Files.readString(WEB.resolve("app.js"));

        assertThat(html).contains("id=\"effect-manifest\"");
        assertThat(javascript).contains("effect.manifestId", "effect.manifestDigest", "approve-manifest",
                "response.status === 409", "await refreshTask()");
    }

    @Test
    void offersOnlyServerValidatedCancelRetryAndFallbackActions() throws Exception {
        String html = Files.readString(WEB.resolve("index.html"));
        String javascript = Files.readString(WEB.resolve("app.js"));

        assertThat(html).contains("id=\"cancel-task-button\"", "id=\"fallback-button\"");
        assertThat(javascript).contains("function isDelegationRetryAuthorized(node)",
                "/delegations/${encodeURIComponent(node.delegationId)}/retry", "/cancel", "/fallback",
                "function executeOperatorCommand(url, reason)");
    }
}
