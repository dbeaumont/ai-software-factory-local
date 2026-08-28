package com.example.aifactory.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TaskState {
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
    public String review;
    public String pullRequestUrl;
    public String error;
    public final List<AgentStep> steps = new ArrayList<>();
    public final Instant createdAt = Instant.now();
    public Instant updatedAt = Instant.now();

    public TaskState(String id, String ticketNumber, TaskRequest request) {
        this.id = id;
        this.ticketNumber = ticketNumber;
        this.request = request;
    }

    public synchronized void transition(TaskStatus newStatus, String summary) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
        this.steps.add(new AgentStep(newStatus.name(), "OK", summary, this.updatedAt));
    }

    public synchronized void fail(Exception ex) {
        this.status = TaskStatus.FAILED;
        this.error = ex.getMessage();
        this.updatedAt = Instant.now();
        this.steps.add(new AgentStep("FAILED", "ERROR", ex.toString(), this.updatedAt));
    }

    public synchronized TaskView view() {
        return new TaskView(id, ticketNumber, status, request.repositoryUrl(), request.effectiveBranch(), request.requirement(),
                request.effectiveLlmMode(), workspace, sourceCommit, model, Map.copyOf(promptFingerprints), plan, patch, testSummary, qualitySummary, securitySummary, review,
                pullRequestUrl, error, List.copyOf(steps), createdAt, updatedAt);
    }
}
