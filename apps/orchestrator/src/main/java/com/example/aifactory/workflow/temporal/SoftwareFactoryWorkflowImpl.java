package com.example.aifactory.workflow.temporal;

import java.util.List;

/** Root durable workflow; subsequent migration steps add child workflows and activities. */
public final class SoftwareFactoryWorkflowImpl implements SoftwareFactoryWorkflow {
    @Override
    public Result run(Request request) {
        requireValid(request);
        return new Result(request.taskId(), request.attemptId(), request.sourceCommit(),
                "READY_FOR_DELEGATION", List.of("WORKFLOW_STARTED"));
    }

    private static void requireValid(Request request) {
        if (request == null || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.requirement() == null || request.requirement().isBlank()) {
            throw new IllegalArgumentException("Software factory workflow request is invalid");
        }
    }
}
