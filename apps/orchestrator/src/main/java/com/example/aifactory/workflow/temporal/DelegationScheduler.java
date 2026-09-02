package com.example.aifactory.workflow.temporal;

import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;

import java.util.Objects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    public List<DelegationWorkflow.Request> validateAndOrder(SoftwareFactoryWorkflow.Request root,
                                                              List<DelegationWorkflow.Request> delegations) {
        Objects.requireNonNull(root, "Root workflow request is required");
        Map<String, DelegationWorkflow.Request> nodes = new LinkedHashMap<>();
        for (DelegationWorkflow.Request node : delegations) {
            if (node == null || node.nodeId() == null || nodes.putIfAbsent(node.nodeId(), node) != null) {
                throw invalid("duplicate or missing node id");
            }
            if (!root.taskId().equals(node.taskId()) || !root.attemptId().equals(node.attemptId())
                    || !root.sourceCommit().equals(node.sourceCommit())) {
                throw invalid("delegation lineage differs from root for " + node.nodeId());
            }
        }
        nodes.values().forEach(node -> dependencies(node, nodes));

        List<DelegationWorkflow.Request> ordered = new ArrayList<>();
        Set<String> completed = new LinkedHashSet<>();
        while (ordered.size() < nodes.size()) {
            boolean progressed = false;
            for (DelegationWorkflow.Request node : nodes.values()) {
                if (!completed.contains(node.nodeId()) && completed.containsAll(dependencies(node, nodes))) {
                    ordered.add(node);
                    completed.add(node.nodeId());
                    progressed = true;
                }
            }
            if (!progressed) throw invalid("cycle detected");
        }
        return List.copyOf(ordered);
    }

    public void requireDependenciesSatisfied(DelegationWorkflow.Request node, Set<String> completedNodeIds,
                                             List<DelegationWorkflow.Request> plan) {
        Map<String, DelegationWorkflow.Request> nodes = new LinkedHashMap<>();
        plan.forEach(item -> nodes.put(item.nodeId(), item));
        Set<String> required = dependencies(node, nodes);
        if (!completedNodeIds.containsAll(required)) {
            throw invalid("dependencies are not satisfied for " + node.nodeId());
        }
    }

    private static Set<String> dependencies(DelegationWorkflow.Request node,
                                            Map<String, DelegationWorkflow.Request> nodes) {
        Set<String> result = new LinkedHashSet<>(node.dependsOn());
        if (node.parentNodeId() != null && !"supervisor".equals(node.parentNodeId())) {
            result.add(node.parentNodeId());
        }
        if (result.contains(node.nodeId())) throw invalid("self dependency for " + node.nodeId());
        for (String dependency : result) {
            if (!nodes.containsKey(dependency)) {
                throw invalid("orphan dependency from " + node.nodeId() + " to " + dependency);
            }
        }
        return Set.copyOf(result);
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("Invalid scheduled DAG: " + message);
    }

    @FunctionalInterface
    interface ChildWorkflowLauncher {
        DelegationWorkflow.Result run(String workflowId, DelegationWorkflow.Request request);
    }
}
