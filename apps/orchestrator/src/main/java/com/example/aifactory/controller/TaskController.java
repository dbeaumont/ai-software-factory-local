package com.example.aifactory.controller;

import com.example.aifactory.a2a.A2aTaskAssociationStore;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskCancellationRequest;
import com.example.aifactory.model.HumanDecisionResponse;
import com.example.aifactory.model.ManifestApprovalRequest;
import com.example.aifactory.model.OperatorActionRequest;
import com.example.aifactory.model.TaskView;
import com.example.aifactory.service.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService tasks;
    private final A2aTaskAssociationStore a2aAssociations;

    public TaskController(TaskService tasks, A2aTaskAssociationStore a2aAssociations) {
        this.tasks = tasks;
        this.a2aAssociations = a2aAssociations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<TaskView> create(@RequestBody TaskRequest request) {
        return tasks.create(request);
    }

    @GetMapping
    public List<TaskView> list() { return tasks.list(); }

    @GetMapping("/{id}")
    public TaskView get(@PathVariable String id) { return tasks.get(id); }

    @GetMapping("/{id}/projection")
    public com.example.aifactory.workflow.TaskMemory.ProjectionStatus projection(@PathVariable String id) {
        return tasks.projectionStatus(id);
    }

    @GetMapping("/{id}/evidence/{artifactId}")
    public TaskView.ArtifactView evidenceSummary(@PathVariable String id, @PathVariable String artifactId) {
        return tasks.get(id).artifacts().stream()
                .filter(artifact -> artifact.artifactId().equals(artifactId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown evidence artifact " + artifactId));
    }

    @GetMapping("/{id}/delegations/{delegationId}/a2a")
    public A2aExecutionTraceView a2aExecutionTrace(@PathVariable String id, @PathVariable String delegationId) {
        TaskView task = tasks.get(id);
        boolean knownDelegation = task.delegations().stream()
                .anyMatch(delegation -> delegation.delegationId().equals(delegationId));
        if (!knownDelegation) throw new IllegalArgumentException("Unknown delegation " + delegationId);
        A2aTaskAssociationStore.Association association = a2aAssociations.findByDelegation(delegationId)
                .filter(candidate -> candidate.taskId().equals(id))
                .orElseThrow(() -> new IllegalArgumentException("No A2A execution for delegation " + delegationId));
        return new A2aExecutionTraceView(association.taskId(), association.attemptId(), association.workflowId(),
                association.workflowRunId(), association.delegationId(), association.agentRole(),
                association.messageId(), association.a2aTaskId(), association.a2aContextId(),
                association.agentCardDigest(), association.sourceCommit(), task.artifacts());
    }

    public record A2aExecutionTraceView(String taskId, String attemptId, String workflowId, String workflowRunId,
                                        String delegationId, String agentRole, String messageId, String a2aTaskId,
                                        String a2aContextId, String agentCardDigest, String sourceCommit,
                                        List<TaskView.ArtifactView> evidence) {}

    @PostMapping("/{id}/approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView approve(@PathVariable String id) { return tasks.approve(id); }

    @PostMapping("/{id}/approve-manifest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView approveManifest(@PathVariable String id, @RequestBody ManifestApprovalRequest request) {
        return tasks.approveManifest(id, request);
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView cancel(@PathVariable String id, @RequestBody TaskCancellationRequest request) {
        return tasks.cancel(id, request);
    }

    @PostMapping("/{id}/decisions/{requestId}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView answerDecision(@PathVariable String id, @PathVariable String requestId,
                                   @RequestBody HumanDecisionResponse response) {
        return tasks.answerDecision(id, requestId, response);
    }

    @PostMapping("/{id}/delegations/{delegationId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskView retryDelegation(@PathVariable String id, @PathVariable String delegationId,
                                    @RequestBody OperatorActionRequest request) {
        return tasks.retryDelegation(id, delegationId, request);
    }

}
