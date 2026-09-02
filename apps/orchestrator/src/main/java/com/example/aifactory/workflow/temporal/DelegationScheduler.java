package com.example.aifactory.workflow.temporal;

import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;

import java.util.Objects;

/** Deterministic scheduling boundary above generic Temporal child workflows. */
public final class DelegationScheduler {
    private final ChildWorkflowLauncher children;

    public DelegationScheduler() {
        this((workflowId, request) -> {
            DelegationWorkflow child = Workflow.newChildWorkflowStub(DelegationWorkflow.class,
                    ChildWorkflowOptions.newBuilder().setWorkflowId(workflowId).build());
            return child.run(request);
        });
    }

    DelegationScheduler(ChildWorkflowLauncher children) {
        this.children = Objects.requireNonNull(children);
    }

    public DelegationWorkflow.Result execute(SoftwareFactoryWorkflow.Request root,
                                             DelegationWorkflow.Request delegation) {
        Objects.requireNonNull(root, "Root workflow request is required");
        Objects.requireNonNull(delegation, "Delegation request is required");
        String workflowId = TemporalIds.delegation(root.taskId(), root.attemptId(), delegation.nodeId());
        return Objects.requireNonNull(children.run(workflowId, delegation),
                "Delegation child returned no result");
    }

    @FunctionalInterface
    interface ChildWorkflowLauncher {
        DelegationWorkflow.Result run(String workflowId, DelegationWorkflow.Request request);
    }
}
