package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HierarchicalPathPlannerTest {
    private final HierarchicalPathPlanner planner = new HierarchicalPathPlanner();

    @Test
    void plansEveryMandatoryR2MultiDomainFixtureWithAllPerimetersAndIndependentReview() throws Exception {
        JsonNode cases = new ObjectMapper().readTree(Files.readString(fixturePath())).path("cases");

        for (JsonNode fixture : cases) {
            if (!"HIERARCHICAL_PATH".equals(fixture.path("expected").path("path").asText())) continue;
            HierarchicalPathPlanner.Plan plan = planner.plan(input(
                    fixture.path("risk").asText(), 2, 2, 1, Set.of(), false)).orElseThrow();

            assertThat(plan.path()).as(fixture.path("id").asText()).isEqualTo("HIERARCHICAL_PATH");
            assertThat(plan.supervisor()).isEqualTo("supervisor");
            assertThat(plan.specialistAgents()).containsExactly(
                    "architecture-agent", "code-agent", "test-agent", "security-agent",
                    "independent-reviewer");
            assertThat(plan.humanGate()).isEqualTo("BEFORE_EXTERNAL_EFFECT");
        }
    }

    @Test
    void buildsTheFullDependencyGraphAndKeepsReviewerOutsideSupervisorAuthority() {
        HierarchicalPathPlanner.Plan plan = planner.plan(input(
                "R2", 1, 1, 2, Set.of(), false)).orElseThrow();

        assertThat(plan.stages()).extracting(HierarchicalPathPlanner.Stage::id)
                .containsExactly("architecture", "code", "tests", "security", "independent-review");
        assertThat(plan.stages()).filteredOn(stage -> "code".equals(stage.id())).singleElement()
                .satisfies(stage -> assertThat(stage.dependsOn()).containsExactly("architecture"));
        assertThat(plan.stages()).filteredOn(stage -> "independent-review".equals(stage.id())).singleElement()
                .satisfies(stage -> {
                    assertThat(stage.parentAuthority()).isEqualTo("workflow");
                    assertThat(stage.dependsOn()).containsExactlyInAnyOrder("code", "tests", "security");
                });
    }

    @Test
    void selectsHierarchyForAnyConfiguredCrossCuttingOrUncertaintyTrigger() {
        assertThat(planner.plan(input("R1", 2, 1, 1, Set.of(), false))).isPresent();
        assertThat(planner.plan(input("R1", 1, 2, 1, Set.of(), false))).isPresent();
        assertThat(planner.plan(input("R1", 1, 1, 2, Set.of(), false))).isPresent();
        assertThat(planner.plan(input("R1", 1, 1, 1, Set.of("public-contract"), false))).isPresent();
        assertThat(planner.plan(input("R1", 1, 1, 1, Set.of(), true))).isPresent();
        assertThat(planner.plan(input("R1", 1, 1, 1, Set.of(), false))).isEmpty();
        assertThat(planner.plan(input("R3", 2, 2, 2, Set.of("authentication"), true))).isEmpty();
    }

    private static HierarchicalPathPlanner.Input input(String risk, int modules, int domains,
                                                       int independentScopes, Set<String> impacts,
                                                       boolean materialDecisionOpen) {
        return new HierarchicalPathPlanner.Input("HIERARCHICAL_ACTIVE", "QUALIFIED", risk,
                modules, domains, independentScopes, impacts, materialDecisionOpen,
                true, true, true, false, true);
    }

    private static Path fixturePath() {
        Path workingDirectory = Path.of("").toAbsolutePath();
        for (Path candidate : java.util.List.of(
                workingDirectory.resolve("resources/multiagents/evaluations/multi-domain-cases-v1.json"),
                workingDirectory.resolve("../../resources/multiagents/evaluations/multi-domain-cases-v1.json")
                        .normalize())) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        throw new IllegalStateException("Cannot find multi-domain evaluation fixtures");
    }
}
