package com.example.aifactory.workflow.temporal;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class A2aWorkerVersioningArchitectureTest {
    @Test
    void pinsA2aWorkflowsAndRegistersOnlyCutoverImplementationsInTheNewBuild() throws Exception {
        String delegation = Files.readString(Path.of(
                "src/main/java/com/example/aifactory/workflow/temporal/A2aDelegationWorkflowImpl.java"));
        String review = Files.readString(Path.of(
                "src/main/java/com/example/aifactory/workflow/temporal/A2aIndependentReviewWorkflowImpl.java"));
        String registry = Files.readString(Path.of(
                "src/main/java/com/example/aifactory/workflow/temporal/TemporalWorkerRegistry.java"));

        assertThat(delegation).contains("@WorkflowVersioningBehavior(VersioningBehavior.PINNED)");
        assertThat(review).contains("@WorkflowVersioningBehavior(VersioningBehavior.PINNED)");
        assertThat(registry).contains(".setUseVersioning(true)", "A2aDelegationWorkflowImpl.class",
                        "A2aIndependentReviewWorkflowImpl.class")
                .doesNotContain("\n                DelegationWorkflowImpl.class",
                        "\n                IndependentReviewWorkflowImpl.class");
    }
}
