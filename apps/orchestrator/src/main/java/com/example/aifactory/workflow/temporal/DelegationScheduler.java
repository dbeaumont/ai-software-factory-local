package com.example.aifactory.workflow.temporal;

import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.Async;

import java.util.Objects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Deterministic scheduling boundary above generic Temporal child workflows. */
public final class DelegationScheduler {
    private static final Comparator<DelegationWorkflow.Request> CONSOLIDATION_ORDER =
            Comparator.comparingInt(DelegationWorkflow.Request::priority)
                    .thenComparing(DelegationWorkflow.Request::nodeId);
    static final int MAX_DEPTH = 2;
    static final int MAX_FAN_OUT = 4;
    static final long MAX_TOTAL_COST_MICROS = 70_000_000;
    static final long MAX_CRITICAL_PATH_SECONDS = 2_700;
    static final int MAX_PARALLEL_DELEGATIONS = 4;
    private final ChildWorkflowLauncher children;

    public DelegationScheduler() {
        this((workflowId, request) -> {
            DelegationWorkflow child = Workflow.newChildWorkflowStub(DelegationWorkflow.class,
                    ChildWorkflowOptions.newBuilder().setWorkflowId(workflowId).build());
            var promise = Async.function(child::run, request);
            return promise::get;
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
        return Objects.requireNonNull(children.start(workflowId, delegation).await(),
                "Delegation child returned no result");
    }

    public List<DelegationWorkflow.Result> executeBatch(SoftwareFactoryWorkflow.Request root,
                                                        List<DelegationWorkflow.Request> batch) {
        if (batch.isEmpty() || batch.size() > MAX_PARALLEL_DELEGATIONS) {
            throw invalid("parallel batch size is outside scheduler quota");
        }
        List<ChildExecution> executions = batch.stream().map(delegation -> children.start(
                TemporalIds.delegation(root.taskId(), root.attemptId(), delegation.nodeId()), delegation)).toList();
        List<DelegationWorkflow.Result> results = new ArrayList<>();
        for (ChildExecution execution : executions) {
            results.add(Objects.requireNonNull(execution.await(), "Delegation child returned no result"));
        }
        return List.copyOf(results);
    }

    public List<DelegationWorkflow.Request> ready(List<DelegationWorkflow.Request> plan,
                                                  Set<String> completedNodeIds, int requestedLimit) {
        int limit = Math.min(requestedLimit, MAX_PARALLEL_DELEGATIONS);
        if (limit < 1) throw invalid("ready-node limit must be positive");
        Map<String, DelegationWorkflow.Request> nodes = new LinkedHashMap<>();
        plan.forEach(node -> nodes.put(node.nodeId(), node));
        List<DelegationWorkflow.Request> ready = plan.stream()
                .filter(node -> !completedNodeIds.contains(node.nodeId()))
                .filter(node -> completedNodeIds.containsAll(dependencies(node, nodes)))
                .sorted(CONSOLIDATION_ORDER)
                .limit(limit).toList();
        if (ready.isEmpty() && completedNodeIds.size() < plan.size()) {
            throw invalid("no dependency-satisfied node is ready");
        }
        return ready;
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
            List<DelegationWorkflow.Request> ready = nodes.values().stream()
                    .filter(node -> !completed.contains(node.nodeId()))
                    .filter(node -> completed.containsAll(dependencies(node, nodes)))
                    .sorted(CONSOLIDATION_ORDER)
                    .toList();
            if (ready.isEmpty()) throw invalid("cycle detected");
            ordered.addAll(ready);
            ready.forEach(node -> completed.add(node.nodeId()));
        }
        requireWithinHardLimits(ordered, nodes);
        return List.copyOf(ordered);
    }

    private static void requireWithinHardLimits(List<DelegationWorkflow.Request> ordered,
                                                Map<String, DelegationWorkflow.Request> nodes) {
        Map<String, Integer> depths = new LinkedHashMap<>();
        Map<String, Integer> children = new LinkedHashMap<>();
        Map<String, Long> durations = new LinkedHashMap<>();
        long totalCost = 0;
        for (DelegationWorkflow.Request node : ordered) {
            String parent = node.parentNodeId();
            int depth = parent == null || "supervisor".equals(parent) ? 1 : depths.get(parent) + 1;
            if (depth > MAX_DEPTH) throw invalid("maximum depth exceeded at " + node.nodeId());
            depths.put(node.nodeId(), depth);
            children.merge(parent == null || "supervisor".equals(parent) ? "$supervisor" : parent,
                    1, Integer::sum);
            if (children.values().stream().anyMatch(count -> count > MAX_FAN_OUT)) {
                throw invalid("maximum fan-out exceeded");
            }
            totalCost = Math.addExact(totalCost, node.budget().maxCostMicros());
            if (totalCost > MAX_TOTAL_COST_MICROS) throw invalid("maximum forecast cost exceeded");
            long predecessor = dependencies(node, nodes).stream().mapToLong(durations::get).max().orElse(0);
            long duration = Math.addExact(predecessor, node.budget().timeoutSeconds());
            if (duration > MAX_CRITICAL_PATH_SECONDS) throw invalid("maximum critical-path duration exceeded");
            durations.put(node.nodeId(), duration);
        }
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
        ChildExecution start(String workflowId, DelegationWorkflow.Request request);
    }

    @FunctionalInterface
    interface ChildExecution {
        DelegationWorkflow.Result await();
    }
}
