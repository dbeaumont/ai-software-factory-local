package com.example.aifactory.workflow.temporal;

import java.util.List;
import java.util.ArrayList;

import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;

/** Root durable workflow; subsequent migration steps add child workflows and activities. */
public final class SoftwareFactoryWorkflowImpl implements SoftwareFactoryWorkflow {
    @Override
    public Result run(Request request) {
        requireValid(request);
        List<String> chronology = new ArrayList<>();
        chronology.add("WORKFLOW_STARTED");
        List<DelegationWorkflow.Result> results = new ArrayList<>();
        for (DelegationWorkflow.Request delegation : request.delegations()) {
            DelegationWorkflow child = Workflow.newChildWorkflowStub(DelegationWorkflow.class,
                    ChildWorkflowOptions.newBuilder().setWorkflowId(delegationId(request, delegation)).build());
            DelegationWorkflow.Result result = child.run(delegation);
            results.add(result);
            chronology.add("DELEGATION_COMPLETED:" + result.nodeId());
        }
        return new Result(request.taskId(), request.attemptId(), request.sourceCommit(),
                results.isEmpty() ? "READY_FOR_DELEGATION" : "DELEGATIONS_COMPLETED", chronology, results);
    }

    private static void requireValid(Request request) {
        if (request == null || request.taskId() == null || !request.taskId().matches("[A-Za-z0-9_-]{1,64}")
                || request.attemptId() == null || !request.attemptId().matches("[A-Za-z0-9_-]{1,128}")
                || request.sourceCommit() == null || !request.sourceCommit().matches("[0-9a-f]{40}")
                || request.requirement() == null || request.requirement().isBlank()) {
            throw new IllegalArgumentException("Software factory workflow request is invalid");
        }
    }

    private static String delegationId(Request root, DelegationWorkflow.Request child) {
        return TemporalIds.delegation(root.taskId(), root.attemptId(), child.nodeId());
    }
}
