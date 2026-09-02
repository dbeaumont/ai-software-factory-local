package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CodeAgentSandboxBoundaryTest {
    @Test
    void developerAndPatchRepairCannotReceiveOrCallSandboxTools() {
        AgentCatalog catalog = new AgentCatalog();
        ToolPermissionMatrix permissions = ToolPermissionMatrix.readOnlyAgents();

        for (String role : List.of("developer", "patch-repair")) {
            assertThat(catalog.require(role).tools()).noneMatch(tool -> tool.startsWith("sandbox."));
            for (String tool : Set.of("sandbox.validate_patch", "sandbox.apply_patch", "sandbox.run_tests",
                    "sandbox.run_quality", "sandbox.run_security", "sandbox.get_execution")) {
                assertThat(AgentRuntime.effectfulTool(tool)).as(tool).isTrue();
                assertThat(permissions.isAllowed(new AgentToolLoop.Actor("task-1", role), tool))
                        .as(role + " -> " + tool).isFalse();
            }
        }

        assertNoSandboxDependency(DeveloperAgent.class);
        assertNoSandboxDependency(PatchRepairAgent.class);
    }

    private static void assertNoSandboxDependency(Class<?> agent) {
        Stream<Class<?>> fields = Arrays.stream(agent.getDeclaredFields()).map(field -> field.getType());
        Stream<Class<?>> parameters = Arrays.stream(agent.getDeclaredConstructors())
                .flatMap((Constructor<?> constructor) -> Arrays.stream(constructor.getParameterTypes()));
        assertThat(Stream.concat(fields, parameters).toList())
                .doesNotContain(SandboxExecutor.class, SandboxGateway.class, McpSandboxService.class);
    }
}
