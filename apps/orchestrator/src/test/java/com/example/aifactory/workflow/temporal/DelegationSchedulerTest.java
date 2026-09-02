package com.example.aifactory.workflow.temporal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DelegationSchedulerTest {
    @Test
    void ownsTheDeterministicChildWorkflowIdentityAndInvocation() {
        List<String> workflowIds = new ArrayList<>();
        List<DelegationWorkflow.Request> requests = new ArrayList<>();
        DelegationScheduler scheduler = new DelegationScheduler((workflowId, request) -> {
            workflowIds.add(workflowId);
            requests.add(request);
            return new DelegationWorkflow.Result(request.nodeId(), request.role(), "READY_FOR_ACTIVITIES");
        });
        SoftwareFactoryWorkflow.Request root = new SoftwareFactoryWorkflow.Request(
                "task-1", "attempt-1", "a".repeat(40), "change");
        DelegationWorkflow.Request child = new DelegationWorkflow.Request(
                "task-1", "attempt-1", "code-1", "supervisor", "code-agent",
                "a".repeat(40), "coordinate code changes");

        DelegationWorkflow.Result result = scheduler.execute(root, child);

        assertThat(workflowIds).containsExactly(TemporalIds.delegation("task-1", "attempt-1", "code-1"));
        assertThat(requests).containsExactly(child);
        assertThat(result).isEqualTo(new DelegationWorkflow.Result(
                "code-1", "code-agent", "READY_FOR_ACTIVITIES"));
    }

    @Test
    void validatesTheWholeDagAndOrdersDependenciesBeforeExecution() {
        DelegationScheduler scheduler = new DelegationScheduler((workflowId, request) ->
                new DelegationWorkflow.Result(request.nodeId(), request.role(), "DONE"));
        SoftwareFactoryWorkflow.Request root = root();
        DelegationWorkflow.Request child = node("child", "parent", Set.of("parent"));
        DelegationWorkflow.Request parent = node("parent", "supervisor", Set.of());

        List<DelegationWorkflow.Request> ordered = scheduler.validateAndOrder(root, List.of(child, parent));

        assertThat(ordered).extracting(DelegationWorkflow.Request::nodeId)
                .containsExactly("parent", "child");
        scheduler.requireDependenciesSatisfied(parent, Set.of(), ordered);
        scheduler.requireDependenciesSatisfied(child, Set.of("parent"), ordered);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                scheduler.requireDependenciesSatisfied(child, Set.of(), ordered))
                .hasMessageContaining("dependencies are not satisfied");
    }

    @Test
    void rejectsDuplicatesOrphansCyclesAndForeignLineageBeforeLaunch() {
        List<String> launched = new ArrayList<>();
        DelegationScheduler scheduler = new DelegationScheduler((workflowId, request) -> {
            launched.add(request.nodeId());
            return new DelegationWorkflow.Result(request.nodeId(), request.role(), "DONE");
        });
        SoftwareFactoryWorkflow.Request root = root();
        DelegationWorkflow.Request one = node("one", "supervisor", Set.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                scheduler.validateAndOrder(root, List.of(one, one))).hasMessageContaining("duplicate");
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                scheduler.validateAndOrder(root, List.of(node("orphan", "supervisor", Set.of("missing")))))
                .hasMessageContaining("orphan");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.validateAndOrder(root, List.of(
                node("a", "supervisor", Set.of("b")), node("b", "supervisor", Set.of("a")))))
                .hasMessageContaining("cycle");
        DelegationWorkflow.Request foreign = new DelegationWorkflow.Request(
                "another-task", "attempt-1", "foreign", "supervisor", "code-agent",
                "a".repeat(40), "foreign", Set.of(), new DelegationWorkflow.Budget(100, 100, 1));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                scheduler.validateAndOrder(root, List.of(foreign))).hasMessageContaining("lineage differs");
        assertThat(launched).isEmpty();
    }

    private static SoftwareFactoryWorkflow.Request root() {
        return new SoftwareFactoryWorkflow.Request("task-1", "attempt-1", "a".repeat(40), "change");
    }

    private static DelegationWorkflow.Request node(String id, String parent, Set<String> dependencies) {
        return new DelegationWorkflow.Request("task-1", "attempt-1", id, parent, "code-agent",
                "a".repeat(40), id, dependencies, new DelegationWorkflow.Budget(100, 100, 1));
    }
}
