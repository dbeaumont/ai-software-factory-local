package com.example.aifactory.workflow;

import com.example.aifactory.model.TaskState;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowCoordinatorTest {
    @Test
    void remainsAFrameworkIndependentApplicationPort() {
        assertThat(WorkflowCoordinator.class.isInterface()).isTrue();
        assertThat(Arrays.stream(WorkflowCoordinator.class.getDeclaredMethods()).map(method -> method.getName()))
                .containsExactlyInAnyOrder("start", "resumeAfterApproval");
        assertThat(Arrays.stream(WorkflowCoordinator.class.getDeclaredMethods())
                .flatMap(method -> Arrays.stream(method.getParameterTypes())))
                .containsOnly(TaskState.class);
        assertThat(WorkflowCoordinator.class.getDeclaredMethods())
                .allMatch(method -> method.getReturnType() == Void.TYPE);
    }
}
