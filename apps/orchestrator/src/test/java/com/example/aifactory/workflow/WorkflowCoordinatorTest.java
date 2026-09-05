package com.example.aifactory.workflow;

import com.example.aifactory.model.TaskState;
import com.example.aifactory.service.DeterministicWorkflowCoordinator;
import com.example.aifactory.workflow.temporal.TemporalWorkflowCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowCoordinatorTest {
    @Test
    void remainsAFrameworkIndependentApplicationPort() {
        assertThat(WorkflowCoordinator.class.isInterface()).isTrue();
        assertThat(Arrays.stream(WorkflowCoordinator.class.getDeclaredMethods()).map(method -> method.getName()))
                .containsExactlyInAnyOrder("start", "resumeAfterApproval", "answerHumanDecision", "cancel");
        assertThat(WorkflowCoordinator.class.getDeclaredMethods())
                .allMatch(method -> method.getParameterTypes()[0] == TaskState.class);
        assertThat(WorkflowCoordinator.class.getDeclaredMethods())
                .allMatch(method -> method.getReturnType() == Void.TYPE);
    }

    @Test
    void exposesTemporalAsTheOnlyProductionCoordinator() {
        assertThat(TemporalWorkflowCoordinator.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(DeterministicWorkflowCoordinator.class.isAnnotationPresent(Component.class)).isFalse();
    }
}
