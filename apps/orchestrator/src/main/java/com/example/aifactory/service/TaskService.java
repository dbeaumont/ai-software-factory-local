package com.example.aifactory.service;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.model.CloudAvailability;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskCancellationRequest;
import com.example.aifactory.model.HumanDecisionResponse;
import com.example.aifactory.model.ManifestApprovalRequest;
import com.example.aifactory.model.OperatorActionRequest;
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
import org.springframework.beans.factory.annotation.Autowired;

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
    private final SecurityAuditJournal audit;

    public TaskService(AiFactoryProperties props, LlmGatewayClient llm, WorkflowCoordinator coordinator, TaskMemory memory,
                       MeterRegistry metrics) {
        this(props, llm, coordinator, memory, metrics, null);
    }

    @Autowired
    public TaskService(AiFactoryProperties props, LlmGatewayClient llm, WorkflowCoordinator coordinator, TaskMemory memory,
                       MeterRegistry metrics, SecurityAuditJournal audit) {
        this.props = props;
        this.llm = llm;
        this.coordinator = coordinator;
        this.memory = memory;
        this.submittedTasks = Counter.builder("ai_factory_tasks_submitted")
                .description("Tasks submitted to the factory").register(metrics);
        this.audit = audit;
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
        if (state.pendingEffect != null && state.pendingEffect.manifestId() != null) {
            throw new IllegalStateException("Manifest-bound approval endpoint is required");
        }
        return approve(state);
    }

    public TaskView approveManifest(String id, ManifestApprovalRequest request) {
        if (request == null || request.manifestId() == null || !request.manifestId().matches("[0-9a-f]{64}")
                || request.manifestDigest() == null || !request.manifestDigest().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Manifest approval request is required");
        }
        TaskState state = requireTask(id);
        synchronized (state) {
            if (state.pendingEffect == null || state.pendingEffect.manifestId() == null
                    || !state.pendingEffect.manifestId().equals(request.manifestId())
                    || !state.pendingEffect.manifestDigest().equals(request.manifestDigest())) {
                throw new IllegalStateException("Approval manifest changed; reload the task before approving");
            }
            return approve(state);
        }
    }

    private TaskView approve(TaskState state) {
        if (state.status != TaskStatus.WAITING_APPROVAL)
            throw new IllegalStateException("Task is not waiting for approval");
        if (state.hasPendingHumanActions())
            throw new IllegalStateException("Human decisions must be answered before approval");
        if (state.pendingEffect == null || !state.pendingEffect.confirmationRequired()
                || !"ALLOW".equals(state.pendingEffect.policyDecision())) {
            throw new IllegalStateException("No policy-approved effect is awaiting confirmation");
        }
        state.humanApproved = true;
        state.transition(TaskStatus.APPROVED, "Human approval recorded");
        if (audit != null) audit.append(SecurityAuditJournal.EventType.APPROVAL, state.id,
                "human-approver", state.pendingEffect.manifestId() == null
                        ? state.pendingEffect.tool() : state.pendingEffect.manifestId(), "APPROVE");
        coordinator.resumeAfterApproval(state);
        return state.view();
    }

    public TaskView cancel(String id, TaskCancellationRequest request) {
        if (request == null) throw new IllegalArgumentException("Cancellation request is required");
        TaskState state = requireTask(id);
        state.cancel(request.reason(), request.actor());
        return state.view();
    }

    public TaskView answerDecision(String id, String requestId, HumanDecisionResponse response) {
        if (response == null) throw new IllegalArgumentException("Human decision response is required");
        TaskState state = requireTask(id);
        state.answerHumanAction(requestId, response.decision(), response.objectDigest(),
                response.actor(), response.actorRole());
        return state.view();
    }

    public TaskView retryDelegation(String id, String delegationId, OperatorActionRequest request) {
        if (request == null) throw new IllegalArgumentException("Operator action request is required");
        TaskState state = requireTask(id);
        state.requestDelegationRetry(delegationId, request.reason(), request.actor());
        return state.view();
    }

    public TaskView fallback(String id, OperatorActionRequest request) {
        if (request == null) throw new IllegalArgumentException("Operator action request is required");
        TaskState state = requireTask(id);
        state.switchToPipelineFallback(request.reason(), request.actor());
        if (audit != null) audit.append(SecurityAuditJournal.EventType.MODE_CHANGE, state.id,
                request.actor(), "execution-mode", "PIPELINE");
        return state.view();
    }

    String nextTicketNumber() {
        return "AF-%04d".formatted(ticketSequence.getAndIncrement());
    }

    private TaskState requireTask(String id) {
        return memory.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown task " + id));
    }

}
