package com.example.aifactory.workflow.temporal;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

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
}
