package com.example.aifactory.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class TaskState {
    public final String id;
    public final TaskRequest request;
    public TaskStatus status = TaskStatus.QUEUED;
    public String workspace;
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

    public TaskState(String id, TaskRequest request) {
        this.id = id;
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
        return new TaskView(id, status, request.repositoryUrl(), request.effectiveBranch(), request.requirement(),
                request.isDryRun(), request.effectiveLlmMode(), workspace, plan, patch, testSummary, qualitySummary, securitySummary, review,
                pullRequestUrl, error, List.copyOf(steps), createdAt, updatedAt);
    }
}
