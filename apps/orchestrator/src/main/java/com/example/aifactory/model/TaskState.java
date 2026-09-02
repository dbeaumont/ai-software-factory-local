package com.example.aifactory.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

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
    private final Map<String, ArtifactMetadata> artifacts = new LinkedHashMap<>();
    public final Map<String, TaskView.ContradictionView> contradictions = new LinkedHashMap<>();
    public final Map<String, TaskView.DecisionView> decisions = new LinkedHashMap<>();
    public final Map<String, TaskView.HumanActionView> humanActions = new LinkedHashMap<>();

    public TaskState(String id, String ticketNumber, TaskRequest request) {
        this.id = id;
        this.ticketNumber = ticketNumber;
        this.request = request;
    }

    public synchronized void transition(TaskStatus newStatus, String summary) {
        if (this.status == TaskStatus.CANCELLED && newStatus != TaskStatus.CANCELLED) {
            throw new CancellationException("Task was cancelled");
        }
        this.status = newStatus;
        this.updatedAt = Instant.now();
        this.steps.add(new AgentStep(newStatus.name(), "OK", summary, this.updatedAt));
        log.info("Task {} ({}) transitioned to {}: {}", id, ticketNumber, newStatus, summary);
    }

    public synchronized void fail(Exception ex) {
        if (this.status == TaskStatus.CANCELLED) return;
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
                        llmTokens, llmCostMicros, agentTurns), List.copyOf(delegations.values()),
                artifacts.values().stream().map(ArtifactMetadata::project).toList(),
                List.copyOf(contradictions.values()), List.copyOf(decisions.values()),
                List.copyOf(humanActions.values()));
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
        recordDelegation(delegationId, parentDelegationId, role, dependsOn, status, stopReason,
                0, 0, 0, 0, List.of());
    }

    public synchronized void recordDelegation(String delegationId, String parentDelegationId, String role,
                                              List<String> dependsOn, String status, String stopReason,
                                              long durationMillis, int turns, long tokens, long costMicros,
                                              List<String> toolsUsed) {
        if (delegationId == null || !delegationId.matches("[A-Za-z0-9_-]{1,128}")
                || parentDelegationId != null && !parentDelegationId.matches("[A-Za-z0-9_-]{1,128}")
                || role == null || !role.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*")
                || dependsOn == null || dependsOn.size() > 16 || dependsOn.stream().distinct().count()
                != dependsOn.size() || dependsOn.stream().anyMatch(value -> value == null
                || !value.matches("[A-Za-z0-9_-]{1,128}")) || dependsOn.contains(delegationId)
                || status == null || status.isBlank() || status.length() > 64
                || stopReason != null && (stopReason.isBlank() || stopReason.length() > 1_000)
                || durationMillis < 0 || turns < 0 || tokens < 0 || costMicros < 0
                || toolsUsed == null || toolsUsed.size() > 32
                || toolsUsed.stream().distinct().count() != toolsUsed.size()
                || toolsUsed.stream().anyMatch(tool -> tool == null || tool.isBlank() || tool.length() > 128)) {
            throw new IllegalArgumentException("Task delegation view is invalid");
        }
        TaskView.DelegationView view = new TaskView.DelegationView(delegationId, parentDelegationId, role,
                dependsOn.stream().sorted().toList(), status, stopReason, durationMillis, turns, tokens,
                costMicros, toolsUsed.stream().sorted().toList(),
                delegations.containsKey(delegationId) ? delegations.get(delegationId).codeImpact() : null);
        delegations.put(delegationId, view);
        updatedAt = Instant.now();
    }

    public synchronized void recordDelegationCodeImpact(String delegationId, List<String> scopes,
                                                        List<String> touchedFiles, List<String> collisions) {
        TaskView.DelegationView current = delegations.get(delegationId);
        if (current == null) throw new IllegalArgumentException("Unknown delegation " + delegationId);
        if (!validPaths(scopes) || !validPaths(touchedFiles) || !validPaths(collisions)) {
            throw new IllegalArgumentException("Task delegation code impact is invalid");
        }
        TaskView.CodeImpactView impact = new TaskView.CodeImpactView(sortedDistinct(scopes),
                sortedDistinct(touchedFiles), sortedDistinct(collisions));
        delegations.put(delegationId, new TaskView.DelegationView(current.delegationId(),
                current.parentDelegationId(), current.role(), current.dependsOn(), current.status(),
                current.stopReason(), current.durationMillis(), current.turns(), current.tokens(),
                current.costMicros(), current.toolsUsed(), impact));
        updatedAt = Instant.now();
    }

    private static boolean validPaths(List<String> paths) {
        return paths != null && paths.size() <= 256 && paths.stream().allMatch(path -> path != null
                && !path.isBlank() && path.length() <= 512 && !path.startsWith("/")
                && !path.contains("\\") && !List.of(path.split("/", -1)).contains(".."));
    }

    private static List<String> sortedDistinct(List<String> values) {
        return values.stream().distinct().sorted().toList();
    }

    public synchronized void recordArtifact(String artifactId, String type, String status, String classification,
                                            String uri, String digest, long sizeBytes, boolean uriAuthorized) {
        if (artifactId == null || !artifactId.matches("[A-Za-z0-9_-]{1,128}")
                || type == null || type.isBlank() || type.length() > 64
                || status == null || !List.of("COMPLETE", "PARTIAL", "CORRUPTED", "MISSING").contains(status)
                || classification == null || !List.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED")
                .contains(classification) || uri == null || !uri.startsWith("evidence://") || uri.length() > 1_024
                || digest == null || !digest.matches("[0-9a-f]{64}") || sizeBytes < 0) {
            throw new IllegalArgumentException("Task artifact metadata is invalid");
        }
        artifacts.put(artifactId, new ArtifactMetadata(artifactId, type, status, classification, uri,
                digest, sizeBytes, uriAuthorized));
        updatedAt = Instant.now();
    }

    public synchronized void recordContradiction(String id, String subject, String type,
                                                 String severity, String status) {
        if (!validProjectionId(id) || subject == null || subject.isBlank() || subject.length() > 1_000
                || !List.of("FACT", "SCOPE", "RISK", "TEST", "RECOMMENDATION").contains(type)
                || !List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(severity)
                || !List.of("OPEN", "RESOLVED", "ESCALATED").contains(status)) {
            throw new IllegalArgumentException("Task contradiction view is invalid");
        }
        contradictions.put(id, new TaskView.ContradictionView(id, subject, type, severity, status));
        updatedAt = Instant.now();
    }

    public synchronized void recordDecision(String id, String contradictionId, String ruleId,
                                            String decision, String author) {
        if (!validProjectionId(id) || !validProjectionId(contradictionId) || !validProjectionId(ruleId)
                || decision == null || decision.isBlank() || decision.length() > 128
                || author == null || author.isBlank() || author.length() > 256) {
            throw new IllegalArgumentException("Task decision view is invalid");
        }
        decisions.put(id, new TaskView.DecisionView(id, contradictionId, ruleId, decision, author));
        updatedAt = Instant.now();
    }

    public synchronized void recordHumanAction(String requestId, String contradictionId, String domain,
                                               String question, String objectDigest, String status) {
        if (!validProjectionId(requestId) || !validProjectionId(contradictionId)
                || !List.of("PRODUCT", "ARCHITECTURE", "SECURITY", "DATA").contains(domain)
                || question == null || question.isBlank() || question.length() > 1_000
                || objectDigest == null || !objectDigest.matches("[0-9a-f]{64}")
                || !List.of("PENDING", "ANSWERED", "APPROVED", "REJECTED", "EXPIRED", "CANCELLED")
                .contains(status)) {
            throw new IllegalArgumentException("Task human action view is invalid");
        }
        humanActions.put(requestId, new TaskView.HumanActionView(requestId, contradictionId, domain,
                question, objectDigest, status));
        updatedAt = Instant.now();
    }

    public synchronized void answerHumanAction(String requestId, String decision, String objectDigest,
                                               String actor, String actorRole) {
        TaskView.HumanActionView action = humanActions.get(requestId);
        if (action == null) throw new IllegalArgumentException("Unknown human decision request " + requestId);
        if (!"PENDING".equals(action.status())) {
            throw new IllegalStateException("Human decision request is not pending");
        }
        if (objectDigest == null || !objectDigest.equals(action.objectDigest())) {
            throw new SecurityException("Human decision object digest does not match");
        }
        if (actorRole == null || !actorRole.equals(action.domain())) {
            throw new SecurityException("Actor role is not authorized for this decision domain");
        }
        if (decision == null || !decision.matches("[A-Z][A-Z0-9_-]{0,127}")
                || actor == null || actor.isBlank() || actor.length() > 256) {
            throw new IllegalArgumentException("Human decision response is invalid");
        }
        humanActions.put(requestId, new TaskView.HumanActionView(action.requestId(), action.contradictionId(),
                action.domain(), action.question(), action.objectDigest(), "ANSWERED"));
        recordDecision("human-" + requestId, action.contradictionId(), "human-decision", decision, actor);
    }

    public synchronized void cancel(String reason, String actor) {
        if (status == TaskStatus.CANCELLED) return;
        if (List.of(TaskStatus.APPROVED, TaskStatus.PR_CREATED, TaskStatus.FAILED).contains(status)) {
            throw new IllegalStateException("Task can no longer be cancelled");
        }
        if (reason == null || reason.isBlank() || reason.length() > 1_000
                || actor == null || actor.isBlank() || actor.length() > 256) {
            throw new IllegalArgumentException("Task cancellation request is invalid");
        }
        humanActions.replaceAll((id, action) -> "PENDING".equals(action.status())
                ? new TaskView.HumanActionView(action.requestId(), action.contradictionId(), action.domain(),
                action.question(), action.objectDigest(), "CANCELLED") : action);
        transition(TaskStatus.CANCELLED, "Cancelled by " + actor + ": " + reason);
    }

    private static boolean validProjectionId(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    }

    private record ArtifactMetadata(String artifactId, String type, String status, String classification,
                                    String uri, String digest, long sizeBytes, boolean uriAuthorized) {
        TaskView.ArtifactView project() {
            return new TaskView.ArtifactView(artifactId, type, status, classification,
                    uriAuthorized ? uri : null, digest, sizeBytes);
        }
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
