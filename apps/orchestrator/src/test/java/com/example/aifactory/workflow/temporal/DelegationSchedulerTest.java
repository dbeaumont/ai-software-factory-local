package com.example.aifactory.workflow.temporal;

import io.temporal.failure.ApplicationFailure;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class DelegationSchedulerTest {
    @Test
    void ownsTheDeterministicChildWorkflowIdentityAndInvocation() {
        List<String> workflowIds = new ArrayList<>();
        List<DelegationWorkflow.Request> requests = new ArrayList<>();
        DelegationScheduler scheduler = new DelegationScheduler((workflowId, request) -> {
            workflowIds.add(workflowId);
            requests.add(request);
            return () -> new DelegationWorkflow.Result(
                    request.nodeId(), request.role(), "READY_FOR_ACTIVITIES");
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
                () -> new DelegationWorkflow.Result(request.nodeId(), request.role(), "DONE"));
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
            return () -> new DelegationWorkflow.Result(request.nodeId(), request.role(), "DONE");
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

    @Test
    void rejectsDepthFanOutForecastCostAndCriticalPathDurationAboveHardLimits() {
        DelegationScheduler scheduler = new DelegationScheduler((workflowId, request) ->
                () -> new DelegationWorkflow.Result(request.nodeId(), request.role(), "DONE"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.validateAndOrder(root(), List.of(
                node("parent", "supervisor", Set.of()), node("child", "parent", Set.of()),
                node("grandchild", "child", Set.of())))).hasMessageContaining("maximum depth");

        List<DelegationWorkflow.Request> fanOut = new ArrayList<>();
        fanOut.add(node("parent", "supervisor", Set.of()));
        for (int index = 1; index <= 5; index++) fanOut.add(node("child-" + index, "parent", Set.of()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.validateAndOrder(root(), fanOut))
                .hasMessageContaining("maximum fan-out");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.validateAndOrder(root(), List.of(
                node("cost-a", "supervisor", Set.of(), new DelegationWorkflow.Budget(100, 40_000_000, 1)),
                node("cost-b", "supervisor", Set.of(), new DelegationWorkflow.Budget(100, 40_000_000, 1)))))
                .hasMessageContaining("forecast cost");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduler.validateAndOrder(root(), List.of(
                node("slow-parent", "supervisor", Set.of(),
                        new DelegationWorkflow.Budget(100, 100, 1, 1_500)),
                node("slow-child", "slow-parent", Set.of(),
                        new DelegationWorkflow.Budget(100, 100, 1, 1_500)))))
                .hasMessageContaining("critical-path duration");
    }

    @Test
    void startsEveryIndependentNodeBeforeAwaitingBatchResults() {
        List<String> started = new ArrayList<>();
        List<String> awaited = new ArrayList<>();
        DelegationScheduler scheduler = new DelegationScheduler((workflowId, request) -> {
            started.add(request.nodeId());
            return () -> {
                assertThat(started).containsExactly("architecture", "code");
                awaited.add(request.nodeId());
                return new DelegationWorkflow.Result(request.nodeId(), request.role(), "DONE");
            };
        });
        List<DelegationWorkflow.Request> batch = List.of(
                node("architecture", "supervisor", Set.of()),
                node("code", "supervisor", Set.of()));

        List<DelegationWorkflow.Result> results = scheduler.executeBatch(root(), batch);

        assertThat(awaited).containsExactly("architecture", "code");
        assertThat(results).extracting(DelegationWorkflow.Result::nodeId)
                .containsExactly("architecture", "code");
    }

    @Test
    void startsArchitectureTestsAndSecurityBeforeAwaitingAnyPerimeter() {
        List<String> started = new ArrayList<>();
        DelegationScheduler scheduler = new DelegationScheduler((workflowId, request) -> {
            started.add(request.role());
            return () -> {
                assertThat(started).containsExactly(
                        "architecture-agent", "test-agent", "security-agent");
                return new DelegationWorkflow.Result(request.nodeId(), request.role(), "DONE");
            };
        });
        List<DelegationWorkflow.Request> independentPerimeters = List.of(
                roleNode("architecture", "architecture-agent"),
                roleNode("tests", "test-agent"),
                roleNode("security", "security-agent"));

        List<DelegationWorkflow.Result> results = scheduler.executeBatch(root(), independentPerimeters);

        assertThat(results).extracting(DelegationWorkflow.Result::role)
                .containsExactly("architecture-agent", "test-agent", "security-agent");
    }

    @Test
    void selectsOnlyDependencySatisfiedNodesAndCapsParallelismAtFour() {
        DelegationScheduler scheduler = new DelegationScheduler((workflowId, request) ->
                () -> new DelegationWorkflow.Result(request.nodeId(), request.role(), "DONE"));
        DelegationWorkflow.Request architecture = node("architecture", "supervisor", Set.of());
        DelegationWorkflow.Request code = node("code", "supervisor", Set.of());
        DelegationWorkflow.Request tests = node("tests", "code", Set.of("architecture"));
        List<DelegationWorkflow.Request> plan = List.of(architecture, code, tests);

        assertThat(scheduler.ready(plan, Set.of(), 10))
                .extracting(DelegationWorkflow.Request::nodeId)
                .containsExactly("architecture", "code");
        assertThat(scheduler.ready(plan, Set.of("architecture", "code"), 10))
                .extracting(DelegationWorkflow.Request::nodeId)
                .containsExactly("tests");

        List<DelegationWorkflow.Request> fiveIndependentNodes = List.of(
                node("one", "supervisor", Set.of()), node("two", "supervisor", Set.of()),
                node("three", "supervisor", Set.of()), node("four", "supervisor", Set.of()),
                node("five", "supervisor", Set.of()));
        assertThat(scheduler.ready(fiveIndependentNodes, Set.of(), 10)).hasSize(4);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                scheduler.executeBatch(root(), fiveIndependentNodes))
                .hasMessageContaining("parallel batch size");
    }

    @Test
    void consolidatesEqualPriorityNodesByStableNodeIdRegardlessOfInputOrder() {
        DelegationScheduler scheduler = new DelegationScheduler((workflowId, request) ->
                () -> new DelegationWorkflow.Result(request.nodeId(), request.role(), "DONE"));
        DelegationWorkflow.Request zeta = prioritizedNode("zeta", 20);
        DelegationWorkflow.Request alpha = prioritizedNode("alpha", 20);
        DelegationWorkflow.Request urgent = prioritizedNode("urgent", 10);

        List<DelegationWorkflow.Request> ordered = scheduler.validateAndOrder(
                root(), List.of(zeta, alpha, urgent));
        List<DelegationWorkflow.Request> ready = scheduler.ready(ordered, Set.of(), 4);
        List<DelegationWorkflow.Result> results = scheduler.executeBatch(root(), ready);

        assertThat(ordered).extracting(DelegationWorkflow.Request::nodeId)
                .containsExactly("urgent", "alpha", "zeta");
        assertThat(results).extracting(DelegationWorkflow.Result::nodeId)
                .containsExactly("urgent", "alpha", "zeta");
    }

    @Test
    void normalizesChildFailuresTimeoutsCancellationsAndIndeterminateResults() {
        DelegationScheduler scheduler = new DelegationScheduler((workflowId, request) -> () -> {
            switch (request.nodeId()) {
                case "failed" -> throw ApplicationFailure.newNonRetryableFailure("invalid output", "CONTRACT");
                case "timeout" -> throw new RuntimeException(new TimeoutException("deadline"));
                case "cancelled" -> throw new CancellationException("cancelled by root");
                default -> {
                    return null;
                }
            }
        });

        List<DelegationWorkflow.Result> results = scheduler.executeBatch(root(), List.of(
                node("failed", "supervisor", Set.of()), node("timeout", "supervisor", Set.of()),
                node("cancelled", "supervisor", Set.of()), node("unknown", "supervisor", Set.of())));

        assertThat(results).extracting(DelegationWorkflow.Result::status)
                .containsExactly("FAILED", "TIMED_OUT", "CANCELLED", "INDETERMINATE");
    }

    @Test
    void propagatesAnUnusableOutcomeTransitivelyWithoutBlockingIndependentNodes() {
        DelegationScheduler scheduler = new DelegationScheduler((workflowId, request) ->
                () -> new DelegationWorkflow.Result(request.nodeId(), request.role(), "DONE"));
        DelegationWorkflow.Request failed = node("failed", "supervisor", Set.of());
        DelegationWorkflow.Request child = node("child", "supervisor", Set.of("failed"));
        DelegationWorkflow.Request grandchild = node("grandchild", "supervisor", Set.of("child"));
        DelegationWorkflow.Request independent = node("independent", "supervisor", Set.of());
        List<DelegationWorkflow.Request> plan = List.of(failed, child, grandchild, independent);
        Map<String, DelegationWorkflow.Result> completed = new LinkedHashMap<>();
        completed.put("failed", new DelegationWorkflow.Result("failed", "code-agent", "FAILED"));

        List<DelegationWorkflow.Result> blocked = scheduler.propagateBlocked(plan, completed);

        assertThat(blocked).extracting(DelegationWorkflow.Result::nodeId)
                .containsExactly("child", "grandchild");
        assertThat(blocked).extracting(DelegationWorkflow.Result::status)
                .containsOnly("BLOCKED_BY_FAILED");
        assertThat(scheduler.ready(plan, Set.of("failed", "child", "grandchild"), 4))
                .extracting(DelegationWorkflow.Request::nodeId)
                .containsExactly("independent");
    }

    @Test
    void reproducesTheSameCoordinationSequenceForTheSameDagAndExternalOutcomes() {
        DelegationWorkflow.Request architecture = node("architecture", "supervisor", Set.of());
        DelegationWorkflow.Request code = node("code", "supervisor", Set.of());
        DelegationWorkflow.Request tests = node("tests", "supervisor", Set.of("code"));
        DelegationWorkflow.Request security = node("security", "supervisor", Set.of("architecture"));
        Map<String, String> externalOutcomes = Map.of(
                "architecture", "FAILED", "code", "DONE", "tests", "DONE", "security", "DONE");

        List<String> first = coordinate(
                List.of(tests, architecture, security, code), externalOutcomes);
        List<String> replay = coordinate(
                List.of(code, security, architecture, tests), externalOutcomes);

        assertThat(replay).isEqualTo(first).containsExactly(
                "START:architecture", "START:code", "RESULT:architecture:FAILED", "RESULT:code:DONE",
                "BLOCKED:security:BLOCKED_BY_FAILED", "START:tests", "RESULT:tests:DONE");
    }

    private static SoftwareFactoryWorkflow.Request root() {
        return new SoftwareFactoryWorkflow.Request("task-1", "attempt-1", "a".repeat(40), "change");
    }

    private static DelegationWorkflow.Request node(String id, String parent, Set<String> dependencies) {
        return node(id, parent, dependencies, new DelegationWorkflow.Budget(100, 100, 1));
    }

    private static DelegationWorkflow.Request node(String id, String parent, Set<String> dependencies,
                                                   DelegationWorkflow.Budget budget) {
        return new DelegationWorkflow.Request("task-1", "attempt-1", id, parent, "code-agent",
                "a".repeat(40), id, dependencies, budget);
    }

    private static DelegationWorkflow.Request prioritizedNode(String id, int priority) {
        return new DelegationWorkflow.Request("task-1", "attempt-1", id, "supervisor", "code-agent",
                "a".repeat(40), id, priority, Set.of(), new DelegationWorkflow.Budget(100, 100, 1));
    }

    private static DelegationWorkflow.Request roleNode(String id, String role) {
        return new DelegationWorkflow.Request("task-1", "attempt-1", id, "supervisor", role,
                "a".repeat(40), id, Set.of(), new DelegationWorkflow.Budget(100, 100, 1));
    }

    private static List<String> coordinate(List<DelegationWorkflow.Request> plan, Map<String, String> outcomes) {
        List<String> sequence = new ArrayList<>();
        DelegationScheduler scheduler = new DelegationScheduler((workflowId, request) -> {
            sequence.add("START:" + request.nodeId());
            return () -> new DelegationWorkflow.Result(
                    request.nodeId(), request.role(), outcomes.get(request.nodeId()));
        });
        List<DelegationWorkflow.Request> ordered = scheduler.validateAndOrder(root(), plan);
        Map<String, DelegationWorkflow.Result> completed = new LinkedHashMap<>();
        while (completed.size() < ordered.size()) {
            for (DelegationWorkflow.Result blocked : scheduler.propagateBlocked(ordered, completed)) {
                completed.put(blocked.nodeId(), blocked);
                sequence.add("BLOCKED:" + blocked.nodeId() + ':' + blocked.status());
            }
            if (completed.size() == ordered.size()) break;
            List<DelegationWorkflow.Result> results = scheduler.executeBatch(
                    root(), scheduler.ready(ordered, completed.keySet(), 4));
            for (DelegationWorkflow.Result result : results) {
                completed.put(result.nodeId(), result);
                sequence.add("RESULT:" + result.nodeId() + ':' + result.status());
            }
        }
        return List.copyOf(sequence);
    }
}
