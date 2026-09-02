package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.CloudAvailability;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskStatus;
import com.example.aifactory.model.TaskView;
import com.example.aifactory.workflow.WorkflowCoordinator;
import com.example.aifactory.workflow.TaskMemory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns task admission, lookup and commands; execution belongs to {@link WorkflowCoordinator}. */
@Service
public class TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private final AtomicInteger ticketSequence = new AtomicInteger(1);
    private final AiFactoryProperties props;
    private final LlmGatewayClient llm;
    private final WorkflowCoordinator coordinator;
    private final TaskMemory memory;
    private final Counter submittedTasks;

    public TaskService(AiFactoryProperties props, LlmGatewayClient llm, WorkflowCoordinator coordinator, TaskMemory memory,
                       MeterRegistry metrics) {
        this.props = props;
        this.llm = llm;
        this.coordinator = coordinator;
        this.memory = memory;
        this.submittedTasks = Counter.builder("ai_factory_tasks_submitted")
                .description("Tasks submitted to the factory").register(metrics);
    }

    public TaskView create(TaskRequest request) {
        if (request.repositoryUrl() == null || request.repositoryUrl().isBlank())
            throw new IllegalArgumentException("repositoryUrl is required");
        if (request.requirement() == null || request.requirement().isBlank())
            throw new IllegalArgumentException("requirement is required");
        if (!props.cloudEnabled()) throw new IllegalArgumentException("Cloud LLM is disabled by configuration");
        CloudAvailability availability = llm.cloudAvailability();
        if (!availability.available()) throw new IllegalStateException(availability.error());
        String id = UUID.randomUUID().toString().substring(0, 8);
        TaskState state = new TaskState(id, nextTicketNumber(), request);
        memory.save(state);
        submittedTasks.increment();
        log.info("Task {} ({}) accepted: mode={}, branch={}", id, state.ticketNumber,
                request.effectiveLlmMode(), request.effectiveBranch());
        coordinator.start(state);
        return state.view();
    }

    public TaskView get(String id) {
        TaskState state = requireTask(id);
        return state.view();
    }

    public List<TaskView> list() {
        return memory.list().stream().map(TaskState::view).toList();
    }

    public TaskView approve(String id) {
        TaskState state = requireTask(id);
        if (state.status != TaskStatus.WAITING_APPROVAL)
            throw new IllegalStateException("Task is not waiting for approval");
        if (state.pendingEffect == null || !state.pendingEffect.confirmationRequired()
                || !"ALLOW".equals(state.pendingEffect.policyDecision())) {
            throw new IllegalStateException("No policy-approved effect is awaiting confirmation");
        }
        state.humanApproved = true;
        state.transition(TaskStatus.APPROVED, "Human approval recorded");
        coordinator.resumeAfterApproval(state);
        return state.view();
    }

    String nextTicketNumber() {
        return "AF-%04d".formatted(ticketSequence.getAndIncrement());
    }

    private TaskState requireTask(String id) {
        return memory.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown task " + id));
    }

}
