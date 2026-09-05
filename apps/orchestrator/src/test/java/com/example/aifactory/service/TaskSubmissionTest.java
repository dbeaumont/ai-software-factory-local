package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.CloudAvailability;
import com.example.aifactory.model.LlmMode;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskStatus;
import com.example.aifactory.workflow.WorkflowCoordinator;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskSubmissionTest {
    private final AiFactoryProperties properties = mock(AiFactoryProperties.class);
    private final LlmGatewayClient llm = mock(LlmGatewayClient.class);
    private final WorkflowCoordinator coordinator = mock(WorkflowCoordinator.class);
    private final InMemoryTaskMemory memory = new InMemoryTaskMemory();
    private final TaskService service = new TaskService(properties, llm, coordinator, memory,
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
    private final TaskRequest request = new TaskRequest(
            "http://gitea:3000/aiadmin/customer-api.git", "main", "change", LlmMode.CLOUD);

    @Test
    void admitsATaskThroughTheNonBlockingAvailabilityProbe() {
        when(properties.cloudEnabled()).thenReturn(true);
        when(llm.cloudAvailabilityAsync()).thenReturn(Mono.just(CloudAvailability.reachable()));

        Mono<com.example.aifactory.model.TaskView> submission = service.create(request);

        verify(llm, never()).cloudAvailabilityAsync();
        com.example.aifactory.model.TaskView task = submission.block(Duration.ofSeconds(1));

        assertThat(task).isNotNull();
        assertThat(task.status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(task.repositoryUrl()).isEqualTo(request.repositoryUrl());
        assertThat(memory.list()).hasSize(1);
        verify(llm).cloudAvailabilityAsync();
        verify(coordinator).start(memory.list().getFirst());
    }

    @Test
    void rejectsATaskWhenTheReactiveAvailabilityProbeFails() {
        when(properties.cloudEnabled()).thenReturn(true);
        when(llm.cloudAvailabilityAsync()).thenReturn(Mono.just(CloudAvailability.unavailable("cloud unavailable")));

        assertThatThrownBy(() -> service.create(request).block(Duration.ofSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("cloud unavailable");

        assertThat(memory.list()).isEmpty();
        verify(coordinator, never()).start(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void canAdmitATaskFromAReactorNonBlockingThread() {
        AtomicBoolean admittedOnNonBlockingThread = new AtomicBoolean();
        when(properties.cloudEnabled()).thenReturn(true);
        when(llm.cloudAvailabilityAsync()).thenReturn(Mono.just(CloudAvailability.reachable()));
        doAnswer(invocation -> {
            admittedOnNonBlockingThread.set(Schedulers.isInNonBlockingThread());
            return null;
        }).when(coordinator).start(org.mockito.ArgumentMatchers.any());

        com.example.aifactory.model.TaskView task = service.create(request)
                .subscribeOn(Schedulers.parallel())
                .block(Duration.ofSeconds(1));

        assertThat(task).isNotNull();
        assertThat(admittedOnNonBlockingThread).isTrue();
    }
}
