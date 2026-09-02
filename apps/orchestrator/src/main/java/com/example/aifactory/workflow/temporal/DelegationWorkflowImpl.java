package com.example.aifactory.workflow.temporal;

/** Generic child workflow shared by typed specialist roles. */
public final class DelegationWorkflowImpl implements DelegationWorkflow {
    @Override
    public Result run(Request request) {
        if (request == null || request.taskId() == null || request.attemptId() == null
                || request.nodeId() == null || !request.nodeId().matches("[A-Za-z0-9_-]{1,128}")
                || request.role() == null || !request.role().matches("[a-z][a-z0-9-]{1,63}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.objective() == null || request.objective().isBlank()) {
            throw new IllegalArgumentException("Delegation workflow request is invalid");
        }
        return new Result(request.nodeId(), request.role(), "READY_FOR_ACTIVITIES");
    }
}
