package com.example.aifactory.workflow.temporal;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface DelegationWorkflow {
    @WorkflowMethod(name = "DelegationWorkflow")
    Result run(Request request);

    record Request(String taskId, String attemptId, String nodeId, String parentNodeId,
                   String role, String sourceCommit, String objective) {}

    record Result(String nodeId, String role, String status) {}
}
