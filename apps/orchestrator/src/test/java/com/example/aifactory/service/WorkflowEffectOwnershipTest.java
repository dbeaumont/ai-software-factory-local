package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowEffectOwnershipTest {
    private static final Path SOURCES = Path.of("src/main/java/com/example/aifactory");

    @Test
    void onlyTheCoordinatorDecidesToRunTestsScansAssuranceAndScmDelivery() throws Exception {
        assertOwners(Map.of(
                "sandbox.test(", List.of("service/DeterministicWorkflowCoordinator.java"),
                "sandbox.quality(", List.of("service/DeterministicWorkflowCoordinator.java"),
                "sandbox.security(", List.of("service/DeterministicWorkflowCoordinator.java"),
                "assurance.requireQualityGate(", List.of("service/DeterministicWorkflowCoordinator.java"),
                "scmDelivery.createDraftPullRequest(", List.of("service/DeterministicWorkflowCoordinator.java")));
    }

    @Test
    void patchValidationAndApplicationRemainBehindTheCoordinatorOwnedIntegrator() throws Exception {
        assertOwners(Map.of(
                "patchIntegrator.validate(", List.of("service/DeterministicWorkflowCoordinator.java",
                        "workflow/temporal/PatchIntegrationActivitiesImpl.java"),
                "patchIntegrator.apply(", List.of("service/DeterministicWorkflowCoordinator.java",
                        "workflow/temporal/PatchIntegrationActivitiesImpl.java"),
                "sandbox.checkPatch(", List.of("service/PatchIntegrator.java"),
                "sandbox.applyPatch(", List.of("service/PatchIntegrator.java")));
    }

    private static void assertOwners(Map<String, List<String>> expected) throws Exception {
        List<Path> sources;
        try (var files = Files.walk(SOURCES)) {
            sources = files.filter(path -> path.toString().endsWith(".java")).toList();
        }
        for (Map.Entry<String, List<String>> rule : expected.entrySet()) {
            List<String> owners = sources.stream().filter(path -> contains(path, rule.getKey()))
                    .map(path -> SOURCES.relativize(path).toString()).sorted().toList();
            assertThat(owners).as("owners of %s", rule.getKey()).isEqualTo(rule.getValue());
        }
    }

    private static boolean contains(Path path, String token) {
        try {
            return Files.readString(path).contains(token);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot inspect " + path, exception);
        }
    }
}
