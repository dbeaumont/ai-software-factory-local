package com.example.aifactory.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TaskState {
    private static final Logger log = LoggerFactory.getLogger(TaskState.class);
    public final String id;
    public final String ticketNumber;
    public final TaskRequest request;
    public TaskStatus status = TaskStatus.QUEUED;
    public String workspace;
    public String sourceCommit;
    public String model;
    public final Map<String, String> promptFingerprints = new LinkedHashMap<>();
    public String plan;
    public String patch;
    public String testSummary;
    public String qualitySummary;
    public String securitySummary;
    public final Map<String, Object> assuranceResults = new LinkedHashMap<>();
    public long llmTokens;
    public long llmCostMicros;
    public int agentTurns;
    public int patchRepairs;
    public boolean testsPassed;
    public boolean reviewAccepted;
    public boolean humanApproved;
    public String review;
    public PendingEffect pendingEffect;
    public String pullRequestUrl;
    public String error;
    public final List<AgentStep> steps = new ArrayList<>();
    public final Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();
    public String executionMode = "PIPELINE";
    public String workflowRunId;
    public String dagVersion = "pipeline-v1";
    public Long globalMaxTokens;
    public Long globalMaxCostMicros;
    public Integer globalMaxTurns;
    public final Map<String, TaskView.DelegationView> delegations = new LinkedHashMap<>();

    public TaskState(String id, String ticketNumber, TaskRequest request) {
        this.id = id;
        this.ticketNumber = ticketNumber;
        this.request = request;
    }

    public synchronized void transition(TaskStatus newStatus, String summary) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
        this.steps.add(new AgentStep(newStatus.name(), "OK", summary, this.updatedAt));
        log.info("Task {} ({}) transitioned to {}: {}", id, ticketNumber, newStatus, summary);
    }

    public synchronized void fail(Exception ex) {
        this.status = TaskStatus.FAILED;
        this.error = ex.getMessage();
        this.updatedAt = Instant.now();
        this.steps.add(new AgentStep("FAILED", "ERROR", ex.toString(), this.updatedAt));
        log.error("Task {} ({}) failed while in {}: {}", id, ticketNumber, status, conciseMessage(ex));
    }

    public synchronized TaskView view() {
        return new TaskView(id, ticketNumber, status, request.repositoryUrl(), request.effectiveBranch(), request.requirement(),
                request.effectiveLlmMode(), workspace, sourceCommit, model, Map.copyOf(promptFingerprints), plan, patch,
                testSummary, qualitySummary, securitySummary, Map.copyOf(assuranceResults), evaluationMetrics(),
                review, pendingEffect,
                pullRequestUrl, error, List.copyOf(steps), createdAt, updatedAt,
                executionMode, workflowRunId, dagVersion,
                new TaskView.GlobalBudget(globalMaxTokens, globalMaxCostMicros, globalMaxTurns,
                        llmTokens, llmCostMicros, agentTurns), List.copyOf(delegations.values()));
    }

    public synchronized void bindExecution(String mode, String runId, String version,
                                           long maxTokens, long maxCostMicros, int maxTurns) {
        if (mode == null || !List.of("PIPELINE", "HIERARCHICAL_SHADOW", "HIERARCHICAL_CANARY",
                "HIERARCHICAL_ACTIVE").contains(mode) || runId == null || runId.isBlank()
                || version == null || version.isBlank() || maxTokens < 1 || maxCostMicros < 0 || maxTurns < 1) {
            throw new IllegalArgumentException("Task execution metadata is invalid");
        }
        executionMode = mode;
        workflowRunId = runId;
        dagVersion = version;
        globalMaxTokens = maxTokens;
        globalMaxCostMicros = maxCostMicros;
        globalMaxTurns = maxTurns;
        updatedAt = Instant.now();
    }

    public synchronized void recordDelegation(String delegationId, String parentDelegationId, String role,
                                              List<String> dependsOn, String status, String stopReason) {
        if (delegationId == null || !delegationId.matches("[A-Za-z0-9_-]{1,128}")
                || parentDelegationId != null && !parentDelegationId.matches("[A-Za-z0-9_-]{1,128}")
                || role == null || !role.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
                || dependsOn == null || dependsOn.size() > 16 || dependsOn.stream().distinct().count()
                != dependsOn.size() || dependsOn.stream().anyMatch(value -> value == null
                || !value.matches("[A-Za-z0-9_-]{1,128}")) || dependsOn.contains(delegationId)
                || status == null || status.isBlank() || status.length() > 64
                || stopReason != null && (stopReason.isBlank() || stopReason.length() > 1_000)) {
            throw new IllegalArgumentException("Task delegation view is invalid");
        }
        TaskView.DelegationView view = new TaskView.DelegationView(delegationId, parentDelegationId, role,
                dependsOn.stream().sorted().toList(), status, stopReason);
        delegations.put(delegationId, view);
        updatedAt = Instant.now();
    }

    public synchronized void recordAgentUsage(int turns, long tokens, long costMicros) {
        this.agentTurns += turns;
        this.llmTokens += tokens;
        this.llmCostMicros += costMicros;
    }

    private Map<String, Object> evaluationMetrics() {
        return Map.of("first_patch_success", patchRepairs == 0, "repairs", patchRepairs,
                "tests_passed", testsPassed, "review_accepted", reviewAccepted,
                "human_accepted", humanApproved, "tokens", llmTokens,
                "cost_micros", llmCostMicros, "agent_turns", agentTurns,
                "duration_millis", java.time.Duration.between(createdAt, updatedAt).toMillis());
    }

    private static String conciseMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return ex.getClass().getSimpleName();
        return message.length() <= 2_000 ? message : message.substring(0, 2_000) + "...[truncated]";
    }
}
