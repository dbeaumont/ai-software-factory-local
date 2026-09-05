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
import com.example.aifactory.workflow.temporal.TemporalCommandConflictException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

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
    private final TicketAdmissionGate admissionGate;

    @Autowired
    public TaskService(AiFactoryProperties props, LlmGatewayClient llm, WorkflowCoordinator coordinator, TaskMemory memory,
                       MeterRegistry metrics, SecurityAuditJournal audit, TicketAdmissionGate admissionGate) {
        this.props = props;
        this.llm = llm;
        this.coordinator = coordinator;
        this.memory = memory;
        this.submittedTasks = Counter.builder("ai_factory_tasks_submitted")
                .description("Tasks submitted to the factory").register(metrics);
        this.audit = audit;
        this.admissionGate = admissionGate;
    }

    public Mono<TaskView> create(TaskRequest request) {
        return Mono.defer(() -> {
            validateSubmission(request);
            return admissionGate.verifyActive()
                    .then(Mono.defer(llm::cloudAvailabilityAsync))
                    .map(availability -> accept(request, availability));
        });
    }

    private void validateSubmission(TaskRequest request) {
        if (request == null) throw new IllegalArgumentException("Task request is required");
        if (request.repositoryUrl() == null || request.repositoryUrl().isBlank())
            throw new IllegalArgumentException("repositoryUrl is required");
        if (request.requirement() == null || request.requirement().isBlank())
            throw new IllegalArgumentException("requirement is required");
        if (!props.cloudEnabled()) throw new IllegalArgumentException("Cloud LLM is disabled by configuration");
    }

    private TaskView accept(TaskRequest request, CloudAvailability availability) {
        if (!availability.available()) throw new IllegalStateException(availability.error());
        String id = UUID.randomUUID().toString().substring(0, 8);
        TaskState state = new TaskState(id, nextTicketNumber(), request);
        memory.admit(state);
        submittedTasks.increment();
        log.info("Task {} ({}) accepted: mode={}, branch={}", id, state.ticketNumber,
                request.effectiveLlmMode(), request.effectiveBranch());
        try {
            coordinator.start(state);
            memory.workflowStarted(state);
        } catch (RuntimeException failure) {
            memory.admissionFailed(state, failure);
            throw failure;
        }
        return state.view();
    }

    public TaskView get(String id) {
        TaskState state = requireTask(id);
        return state.view();
    }

    public List<TaskView> list() {
        return memory.list().stream().map(TaskState::view).toList();
    }

    public TaskMemory.ProjectionStatus projectionStatus(String id) {
        requireTask(id);
        return memory.projectionStatus(id)
                .orElseThrow(() -> new IllegalStateException("Task projection status is unavailable"));
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
            if (state.pendingEffect == null || state.pendingEffect.manifestId() == null) {
                throw new TemporalCommandConflictException(
                        TemporalCommandConflictException.Reason.PROJECTION_LAG,
                        "Approval manifest is not projected yet; reload the task");
            }
            if (!state.pendingEffect.manifestId().equals(request.manifestId())
                    || !state.pendingEffect.manifestDigest().equals(request.manifestDigest())) {
                throw new TemporalCommandConflictException(TemporalCommandConflictException.Reason.STALE_DIGEST,
                        "Approval manifest changed; reload the task before approving");
            }
            if (state.approvalExpiresAt != null && !state.approvalExpiresAt.isAfter(java.time.Instant.now())) {
                throw new TemporalCommandConflictException(TemporalCommandConflictException.Reason.APPROVAL_EXPIRED,
                        "Approval manifest has expired; request a new attempt");
            }
            return approve(state);
        }
    }

    private TaskView approve(TaskState state) {
        if (state.humanApproved && (state.status == TaskStatus.APPROVED || state.status == TaskStatus.PR_CREATED)) {
            return state.view();
        }
        if (state.status != TaskStatus.WAITING_APPROVAL)
            throw new IllegalStateException("Task is not waiting for approval");
        if (state.hasPendingHumanActions())
            throw new IllegalStateException("Human decisions must be answered before approval");
        if (state.pendingEffect == null || !state.pendingEffect.confirmationRequired()
                || !"ALLOW".equals(state.pendingEffect.policyDecision())) {
            throw new IllegalStateException("No policy-approved effect is awaiting confirmation");
        }
        String object = state.pendingEffect.manifestId() == null
                ? state.pendingEffect.tool() : state.pendingEffect.manifestId();
        auditCommand(state, "human-approver", "APPROVE", object,
                () -> coordinator.resumeAfterApproval(state));
        if (audit != null) audit.append(SecurityAuditJournal.EventType.APPROVAL, state.id,
                "human-approver", correlated(state, "APPROVE", object), "APPROVE");
        return state.view();
    }

    public TaskView cancel(String id, TaskCancellationRequest request) {
        if (request == null) throw new IllegalArgumentException("Cancellation request is required");
        TaskState state = requireTask(id);
        auditCommand(state, request.actor(), "CANCEL", "task",
                () -> coordinator.cancel(state, request));
        return state.view();
    }

    public TaskView answerDecision(String id, String requestId, HumanDecisionResponse response) {
        if (response == null) throw new IllegalArgumentException("Human decision response is required");
        TaskState state = requireTask(id);
        auditCommand(state, response.actor(), "HUMAN_DECISION", requestId + ':' + response.objectDigest(),
                () -> coordinator.answerHumanDecision(state, requestId, response));
        return state.view();
    }

    public TaskView retryDelegation(String id, String delegationId, OperatorActionRequest request) {
        if (request == null) throw new IllegalArgumentException("Operator action request is required");
        TaskState state = requireTask(id);
        auditCommand(state, request.actor(), "RETRY", delegationId,
                () -> coordinator.retry(state, delegationId, request));
        return state.view();
    }

    private void auditCommand(TaskState state, String actor, String operation, String object, Runnable command) {
        String reference = correlated(state, operation, object);
        if (audit != null) audit.append(SecurityAuditJournal.EventType.COMMAND_INTENT,
                state.id, actor, reference, "REQUESTED");
        try {
            command.run();
            if (audit != null) audit.append(SecurityAuditJournal.EventType.COMMAND_ACCEPTED,
                    state.id, actor, reference, "ACCEPTED");
        } catch (RuntimeException failure) {
            if (audit != null) audit.append(SecurityAuditJournal.EventType.COMMAND_REJECTED,
                    state.id, actor, reference, failure.getClass().getSimpleName());
            throw failure;
        }
    }

    private static String correlated(TaskState state, String operation, String object) {
        return state.workflowAttemptId + '/' + operation + '/' + object;
    }

    String nextTicketNumber() {
        return "AF-%04d".formatted(ticketSequence.getAndIncrement());
    }

    private TaskState requireTask(String id) {
        return memory.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown task " + id));
    }

}
