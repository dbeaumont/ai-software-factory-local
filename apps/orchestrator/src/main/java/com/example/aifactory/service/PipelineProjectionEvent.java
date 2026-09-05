package com.example.aifactory.service;

import com.example.aifactory.model.PendingEffect;
import com.example.aifactory.model.TaskState;

import java.util.List;
import java.util.Map;

/** Explicit business events emitted by pipeline steps and applied to the task read projection. */
public sealed interface PipelineProjectionEvent {
    record AgentMetadata(Map<String, String> promptFingerprints, long tokens, long costMicros, int turns) {
        public AgentMetadata {
            promptFingerprints = Map.copyOf(promptFingerprints);
            if (tokens < 0 || costMicros < 0 || turns < 0) throw new IllegalArgumentException("Invalid agent usage");
        }

        static AgentMetadata none() {
            return new AgentMetadata(Map.of(), 0, 0, 0);
        }

        AgentMetadata plus(AgentMetadata other) {
            var fingerprints = new java.util.LinkedHashMap<>(promptFingerprints);
            fingerprints.putAll(other.promptFingerprints);
            return new AgentMetadata(fingerprints, Math.addExact(tokens, other.tokens),
                    Math.addExact(costMicros, other.costMicros), Math.addExact(turns, other.turns));
        }
    }

    record WorkspaceInitialized(String workspace) implements PipelineProjectionEvent {}
    record SourceCloned(String sourceCommit, String model) implements PipelineProjectionEvent {}
    record PlanProduced(String plan, AgentMetadata agent) implements PipelineProjectionEvent {}
    record PatchProduced(String patch, int repairs, AgentMetadata agent) implements PipelineProjectionEvent {}
    record TestsCompleted(String summary, Map<String, Object> assurance, AgentMetadata agent)
            implements PipelineProjectionEvent {
        public TestsCompleted { assurance = Map.copyOf(assurance); }
    }
    record QualityCompleted(String summary, Map<String, Object> assurance) implements PipelineProjectionEvent {
        public QualityCompleted { assurance = Map.copyOf(assurance); }
    }
    record SecurityCompleted(String summary, Map<String, Object> assurance) implements PipelineProjectionEvent {
        public SecurityCompleted { assurance = Map.copyOf(assurance); }
    }
    record ReviewCompleted(String review, AgentMetadata agent) implements PipelineProjectionEvent {}
    record DeliveryPrepared(PendingEffect pendingEffect) implements PipelineProjectionEvent {}
    record PullRequestCreated(String pullRequestUrl) implements PipelineProjectionEvent {}

    record StepExecution(PipelineStepContracts.Result result, List<PipelineProjectionEvent> events) {
        public StepExecution {
            events = List.copyOf(events);
            if (result == null || events.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("Step execution is invalid");
            }
        }

        static StepExecution of(PipelineStepContracts.Result result, PipelineProjectionEvent event) {
            return new StepExecution(result, List.of(event));
        }
    }

    final class Applier {
        private Applier() {}

        public static void apply(TaskState state, PipelineProjectionEvent event) {
            switch (event) {
                case WorkspaceInitialized value -> state.workspace = value.workspace();
                case SourceCloned value -> {
                    state.sourceCommit = value.sourceCommit();
                    state.model = value.model();
                }
                case PlanProduced value -> {
                    state.plan = value.plan();
                    applyAgent(state, value.agent());
                }
                case PatchProduced value -> {
                    state.patch = value.patch();
                    state.patchRepairs += value.repairs();
                    applyAgent(state, value.agent());
                }
                case TestsCompleted value -> {
                    state.testSummary = value.summary();
                    state.testsPassed = true;
                    state.assuranceResults.putAll(value.assurance());
                    applyAgent(state, value.agent());
                }
                case QualityCompleted value -> {
                    state.qualitySummary = value.summary();
                    state.assuranceResults.putAll(value.assurance());
                }
                case SecurityCompleted value -> {
                    state.securitySummary = value.summary();
                    state.assuranceResults.putAll(value.assurance());
                }
                case ReviewCompleted value -> {
                    state.review = value.review();
                    state.reviewAccepted = true;
                    applyAgent(state, value.agent());
                }
                case DeliveryPrepared value -> state.pendingEffect = value.pendingEffect();
                case PullRequestCreated value -> state.pullRequestUrl = value.pullRequestUrl();
            }
            state.updatedAt = java.time.Instant.now();
        }

        private static void applyAgent(TaskState state, AgentMetadata agent) {
            state.promptFingerprints.putAll(agent.promptFingerprints());
            state.recordAgentUsage(agent.turns(), agent.tokens(), agent.costMicros());
        }
    }
}
