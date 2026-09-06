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
    private String continuationMessageId;
    private String continuationEnvelope;
    private String currentState = "SUBMITTED";
    private final AgentTaskProjectionActivities projections = Workflow.newActivityStub(
            AgentTaskProjectionActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(java.time.Duration.ofSeconds(30))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(10).build()).build());
    private final AgentArtifactActivities artifacts = Workflow.newActivityStub(
            AgentArtifactActivities.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(java.time.Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(10).build()).build());

    @Override
    @WorkflowVersioningBehavior(VersioningBehavior.PINNED)
    public Outcome run(Input input) {
        requireInput(input);
        currentState = "WORKING";
        projections.project(new AgentTaskProjectionActivities.Projection(
                input.taskId(), currentState, input.taskId() + ":working", input.traceparent()));
        Workflow.await(() -> outcome != null || canceled);
        Outcome terminal = canceled ? new Outcome("CANCELED", null, cancellationReason) : outcome;
        if ("COMPLETED".equals(terminal.state())) {
            AgentArtifactActivities.ArtifactReference artifact = artifacts.publish(
                    new AgentArtifactActivities.PublishCommand(input.taskId(), terminal.attemptId(), input.role(),
                            terminal.outputContract(), terminal.allowedReferenceIds(), terminal.artifactContentBase64(),
                            terminal.artifactDigest()));
            terminal = new Outcome(terminal.state(), artifact.digest(), artifact.uri(), terminal.attemptId(),
                    terminal.outputContract(), terminal.allowedReferenceIds(), null);
        }
        currentState = terminal.state();
        projections.project(new AgentTaskProjectionActivities.Projection(
                input.taskId(), currentState, input.taskId() + ":terminal:" + currentState, input.traceparent()));
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
    public void continueWith(String messageId, String envelopeJson) {
        if (messageId == null || messageId.isBlank() || envelopeJson == null || envelopeJson.isBlank()) {
            throw new IllegalArgumentException("A2A continuation is incomplete");
        }
        continuationMessageId = messageId;
        continuationEnvelope = envelopeJson;
        currentState = "WORKING";
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
        if (input.traceparent() != null) new A2aW3cTraceContext(input.traceparent(), input.baggage());
    }

    private static Outcome requireOutcome(Outcome outcome) {
        if (outcome == null || outcome.state() == null
                || !java.util.Set.of("COMPLETED", "REJECTED", "FAILED").contains(outcome.state())) {
            throw new IllegalArgumentException("Agent task workflow outcome is incomplete");
        }
        if ("COMPLETED".equals(outcome.state())
                && (outcome.artifactDigest() == null || !outcome.artifactDigest().matches("[0-9a-f]{64}")
                || outcome.attemptId() == null || outcome.attemptId().isBlank()
                || outcome.outputContract() == null || outcome.outputContract().isBlank()
                || outcome.artifactContentBase64() == null || outcome.artifactContentBase64().isBlank())) {
            throw new IllegalArgumentException("Completed agent task lacks its validated artifact");
        }
        return outcome;
    }
}
