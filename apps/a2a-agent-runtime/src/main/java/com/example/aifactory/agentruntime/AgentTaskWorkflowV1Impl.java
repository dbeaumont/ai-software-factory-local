package com.example.aifactory.agentruntime;

import io.temporal.common.VersioningBehavior;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowVersioningBehavior;

/** Deterministic lifecycle shell; execution/projection activities are attached in subsequent tickets. */
public final class AgentTaskWorkflowV1Impl implements AgentTaskWorkflowV1 {
    private Outcome outcome;
    private boolean canceled;
    private String cancellationReason;
    private String currentState = "SUBMITTED";
    private final AgentTaskProjectionActivities projections = Workflow.newActivityStub(
            AgentTaskProjectionActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(java.time.Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(10).build()).build());

    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public Outcome run(Input input) {
        requireInput(input);
        currentState = "WORKING";
        projections.project(new AgentTaskProjectionActivities.Projection(
                input.taskId(), currentState, input.taskId() + ":working"));
        Workflow.await(() -> outcome != null || canceled);
        Outcome terminal = canceled ? new Outcome("CANCELED", null, cancellationReason) : outcome;
        currentState = terminal.state();
        projections.project(new AgentTaskProjectionActivities.Projection(
                input.taskId(), currentState, input.taskId() + ":terminal:" + currentState));
        return terminal;
    }

    @Override
    public void complete(Outcome outcome) {
        if (this.outcome == null && !canceled) this.outcome = requireOutcome(outcome);
    }

    @Override
    public void cancel(String reason) {
        if (outcome == null && !canceled) {
            canceled = true;
            cancellationReason = reason == null ? "canceled" : reason;
        }
    }

    @Override
    public String state() {
        return currentState;
    }

    private static void requireInput(Input input) {
        if (input == null || input.taskId() == null || input.taskId().isBlank()
                || input.contextId() == null || input.contextId().isBlank()
                || input.role() == null || input.role().isBlank() || input.skill() == null || input.skill().isBlank()
                || input.envelopeJson() == null || input.envelopeJson().isBlank()) {
            throw new IllegalArgumentException("Agent task workflow input is incomplete");
        }
    }

    private static Outcome requireOutcome(Outcome outcome) {
        if (outcome == null || outcome.state() == null
                || !java.util.Set.of("COMPLETED", "REJECTED", "FAILED").contains(outcome.state())) {
            throw new IllegalArgumentException("Agent task workflow outcome is incomplete");
        }
        return outcome;
    }
}
