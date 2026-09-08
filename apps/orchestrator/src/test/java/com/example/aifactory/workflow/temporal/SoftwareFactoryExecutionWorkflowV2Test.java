package com.example.aifactory.workflow.temporal;

import com.example.aifactory.model.TaskRoutingFacts;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SoftwareFactoryExecutionWorkflowV2Test {
    @Test
    void keepsExecutionModeOutOfThePersistedV2Contract() {
        assertThat(Arrays.stream(SoftwareFactoryExecutionWorkflowV2.Request.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("executionMode", "requestedMode", "effectiveMode");
    }

    @Test
    void makesHierarchicalExecutionImplicitAtTheV1CompatibilityBoundary() {
        var request = new SoftwareFactoryExecutionWorkflowV2.Request(
                "task-1", "attempt-1", "customer-api", "UNRESOLVED", "requirement",
                new SoftwareFactoryWorkflow.SourceLocation("http://gitea/repo.git", "main", "context", Map.of()),
                null, TaskRoutingFacts.qualifiedLowRiskFixture());

        assertThat(request.requirementDigest()).matches("[0-9a-f]{64}");
        assertThat(request.hierarchicalRequest().executionMode())
                .isEqualTo(SoftwareFactoryWorkflow.WorkflowExecutionMode.HIERARCHICAL_ACTIVE);
    }

    @Test
    void resolvesTheSourceThenFailsClosedOnThePersistedHumanTriageDecision() {
        AtomicInteger resolutions = new AtomicInteger();
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            var workflowWorker = environment.newWorker("test-workflow");
            workflowWorker.registerWorkflowImplementationTypes(SoftwareFactoryExecutionWorkflowV2Impl.class);
            var contextWorker = environment.newWorker("test-context");
            contextWorker.registerActivitiesImplementations(new SourceResolutionActivities() {
                @Override public Result resolve(Request request) {
                    resolutions.incrementAndGet();
                    return new Result(request.repositoryId(), request.branch(), "a".repeat(40),
                            "/workspace/task-1", "b".repeat(64));
                }
            }, (HierarchicalRoutingActivities) request -> new HierarchicalRoutingActivities.Decision(
                    "c".repeat(64), "routing-policy-v1", "1", Map.of("risk", "R4"),
                    "human-triage", "HUMAN_TRIAGE", List.of("Risk requires triage"), List.of(),
                    "BEFORE_CODE"));
            environment.start();
            SoftwareFactoryExecutionWorkflowV2 workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryExecutionWorkflowV2.class, WorkflowOptions.newBuilder()
                            .setWorkflowId("ai-factory/task-1/attempt-1")
                            .setTaskQueue("test-workflow").build());
            TaskRoutingFacts facts = new TaskRoutingFacts("QUALIFIED", "R4", 1, 1, 1, 1,
                    java.util.Set.of(), false, true, false, true);

            SoftwareFactoryWorkflow.Result result = workflow.run(new SoftwareFactoryExecutionWorkflowV2.Request(
                    "task-1", "attempt-1", "customer-api", "UNRESOLVED", "requirement",
                    new SoftwareFactoryWorkflow.SourceLocation(
                            "http://gitea:3000/aiadmin/customer-api.git", "main", "test-context",
                            Map.of("context", "test-context")), null, facts));

            assertThat(result.status()).isEqualTo("HUMAN_TRIAGE");
            assertThat(result.chronology()).contains("ROUTING_DECIDED:" + "c".repeat(64) + ":HUMAN_TRIAGE");
            assertThat(resolutions).hasValue(1);
        }
    }
}
