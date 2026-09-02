package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndependentReviewerBoundaryTest {
    @Test
    void cannotDelegateOrReceiveReplanMutationAndDeliveryCapabilities() {
        AgentCatalog.Role role = new AgentCatalog().require("independent-reviewer");
        ToolPermissionMatrix permissions = ToolPermissionMatrix.readOnlyAgents();
        AgentToolLoop.Actor actor = new AgentToolLoop.Actor("task-1", role.name());

        assertThat(role.parent()).isEqualTo("workflow");
        assertThat(role.mayDelegateTo()).isEmpty();
        assertThat(role.effectful()).isFalse();
        assertThat(role.tools()).noneMatch(AgentRuntime::effectfulTool);
        for (String tool : Set.of("sandbox.validate_patch", "sandbox.apply_patch", "sandbox.run_tests",
                "assurance.evaluate_quality_gate", "assurance.evaluate_policy",
                "evidence.store", "evidence.create_manifest", "scm.create_draft_pull_request")) {
            assertThat(AgentRuntime.effectfulTool(tool)).as(tool).isTrue();
            assertThat(permissions.isAllowed(actor, tool)).as(tool).isFalse();
        }
        assertThat(Arrays.stream(IndependentReviewerAgent.Request.class.getRecordComponents())
                .map(component -> component.getName()).toList())
                .doesNotContain("operation", "delegationPlan", "replacementPlan", "patch", "delivery");
        assertNoEffectDependency(IndependentReviewerAgent.class);
    }

    @Test
    void outputContractRejectsReplanningMutationAndDeliveryInstructions() throws Exception {
        MultiAgentContractValidator contracts = new MultiAgentContractValidator(new ObjectMapper());
        JsonNode review = new ObjectMapper().readTree(Files.readString(fixture()))
                .path("documents").path("independent-review-v1");

        for (String forbidden : List.of("replacement_plan_id", "patch", "delivery")) {
            ObjectNode altered = (ObjectNode) review.deepCopy();
            altered.put(forbidden, "forbidden");
            assertThatThrownBy(() -> contracts.validate("independent-review-v1", altered))
                    .as(forbidden).hasMessageContaining("violates");
        }
    }

    private static void assertNoEffectDependency(Class<?> agent) {
        Set<Class<?>> forbidden = Set.of(SupervisorAgent.class, SandboxExecutor.class, SandboxGateway.class,
                McpSandboxService.class, PatchIntegrator.class, AssuranceGateway.class, ScmDeliveryGateway.class);
        Stream<Class<?>> fields = Arrays.stream(agent.getDeclaredFields()).map(field -> field.getType());
        Stream<Class<?>> parameters = Arrays.stream(agent.getDeclaredConstructors())
                .flatMap((Constructor<?> constructor) -> Arrays.stream(constructor.getParameterTypes()));
        assertThat(Stream.concat(fields, parameters).filter(forbidden::contains).toList()).isEmpty();
    }

    private static Path fixture() {
        return Path.of(System.getProperty("user.dir"))
                .resolve("../../resources/multiagents/fixtures/golden-contracts-v1.json").normalize();
    }
}
