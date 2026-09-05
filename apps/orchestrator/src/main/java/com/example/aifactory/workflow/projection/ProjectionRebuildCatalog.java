package com.example.aifactory.workflow.projection;

import java.util.List;

/** Bounded, ordered source of Temporal executions eligible for projection reconstruction. */
public interface ProjectionRebuildCatalog {
    List<Candidate> pageAfter(String taskId, int limit);

    record Candidate(String taskId, String workflowId, String runId) {}
}
