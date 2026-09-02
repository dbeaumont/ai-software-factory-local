package com.example.aifactory.service;

import com.example.aifactory.workflow.WorkflowCoordinator;
import com.example.aifactory.workflow.TaskMemory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TaskServiceBoundaryTest {
    @Test
    void exposesOnlyTaskCreationQueriesAndCommands() {
        Set<String> publicMethods = Arrays.stream(TaskService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(method -> method.getName())
                .collect(Collectors.toSet());
        assertThat(publicMethods).containsExactlyInAnyOrder(
                "create", "get", "list", "approve", "approveManifest", "cancel", "answerDecision",
                "retryDelegation", "fallback");
        assertThat(Arrays.stream(TaskService.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == WorkflowCoordinator.class)).isTrue();
        assertThat(Arrays.stream(TaskService.class.getDeclaredFields())
                .anyMatch(field -> field.getType() == TaskMemory.class)).isTrue();
        assertThat(Arrays.stream(TaskService.class.getDeclaredFields()).map(field -> field.getType())
                .noneMatch(type -> type == SandboxExecutor.class || type == AssuranceGateway.class
                        || type == ScmDeliveryGateway.class || type == AgentContextToolHost.class)).isTrue();
    }
}
