package com.example.aifactory.workflow.temporal;

import io.temporal.common.VersioningBehavior;
import io.temporal.workflow.WorkflowVersioningBehavior;

/** Workflow launched only by the root workflow after specialist consolidation. */
public final class IndependentReviewWorkflowImpl implements IndependentReviewWorkflow {
    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public Result run(Request request) {
        if (request == null || request.taskId() == null || request.attemptId() == null
                || request.reviewId() == null || !request.reviewId().matches("[A-Za-z0-9_-]{1,128}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.bundle() == null || !request.bundle().boundTo(
                request.taskId(), request.attemptId(), request.sourceCommit())) {
            throw new IllegalArgumentException("Independent review workflow request is invalid");
        }
        return new Result(request.reviewId(), "independent-reviewer", "READY_FOR_ACTIVITIES");
    }
}
