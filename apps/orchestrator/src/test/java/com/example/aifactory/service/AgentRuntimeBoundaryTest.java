package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRuntimeBoundaryTest {
    @Test
    void hasNoDirectEffectClientDependency() {
        Set<Class<?>> forbidden = Set.of(SandboxExecutor.class, PatchIntegrator.class,
                AssuranceGateway.class, ScmDeliveryGateway.class, McpSandboxService.class, SandboxGateway.class);
        Stream<Class<?>> fieldTypes = Arrays.stream(AgentRuntime.class.getDeclaredFields()).map(field -> field.getType());
        Stream<Class<?>> parameterTypes = Arrays.stream(AgentRuntime.class.getDeclaredConstructors())
                .flatMap((Constructor<?> constructor) -> Arrays.stream(constructor.getParameterTypes()));

        assertThat(Stream.concat(fieldTypes, parameterTypes).filter(forbidden::contains).toList()).isEmpty();
    }
}
