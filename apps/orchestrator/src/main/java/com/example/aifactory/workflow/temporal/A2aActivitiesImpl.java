package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aClient;
import com.example.aifactory.a2a.A2aContractMapping;
import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aMediaTypes;
import com.example.aifactory.a2a.A2aTaskAssociationStore;
import com.example.aifactory.a2a.A2aW3cTraceContext;
import com.example.aifactory.a2a.A2aTelemetryCorrelation;
import com.example.aifactory.a2a.A2aClientMetrics;
import com.example.aifactory.a2a.A2aSpanLinks;
import com.example.aifactory.a2a.AgentCardResolver;
import com.example.aifactory.a2a.A2aEvidenceUriPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

public final class A2aActivitiesImpl implements A2aActivities.ResolveAgent, A2aActivities.DispatchTask,
        A2aActivities.GetTask, A2aActivities.CancelTask, A2aActivities.ValidateArtifacts,
        A2aActivities.ReconcileDispatch, A2aActivities.ContinueTask {
    private final AgentCardResolver cards;
    private final A2aClient client;
    private final A2aContractMapping contracts;
    private final A2aTaskAssociationStore associations;
    private final A2aClientMetrics metrics;
    private final A2aSpanLinks spanLinks;

    public A2aActivitiesImpl(AgentCardResolver cards, A2aClient client, A2aContractMapping contracts,
                             A2aTaskAssociationStore associations) {
        this(cards, client, contracts, associations, A2aClientMetrics.disabled(), A2aSpanLinks.global());
    }

    public A2aActivitiesImpl(AgentCardResolver cards, A2aClient client, A2aContractMapping contracts,
                             A2aTaskAssociationStore associations, A2aClientMetrics metrics) {
        this(cards, client, contracts, associations, metrics, A2aSpanLinks.global());
    }

    public A2aActivitiesImpl(AgentCardResolver cards, A2aClient client, A2aContractMapping contracts,
                             A2aTaskAssociationStore associations, A2aClientMetrics metrics,
                             A2aSpanLinks spanLinks) {
        this.cards = cards;
        this.client = client;
        this.contracts = contracts;
        this.associations = associations;
        this.metrics = metrics;
        this.spanLinks = spanLinks;
    }

    @Override
    public A2aContracts.AgentCardDescriptor resolveAgent(String agentRole) {
        try {
            A2aContracts.AgentCardDescriptor card = metrics.call(agentRole, "none", "card",
                    () -> await(cards.resolve(agentRole), Duration.ofSeconds(20)));
            metrics.cardValidation(agentRole, "accepted");
            return card;
        } catch (RuntimeException failure) {
            metrics.cardValidation(agentRole, "rejected");
            throw failure;
        }
    }

    @Override
    public A2aContracts.TaskSnapshot dispatchTask(A2aActivities.DispatchRequest request) {
        requireDispatch(request);
        return spanLinks.call("ai.factory.a2a.dispatch", "temporal-to-a2a", null, Map.of(
                "ai_factory.task.id", request.execution().taskId(),
                "temporal.workflow.id", request.execution().workflowId(),
                "a2a.message.id", request.command().messageId(),
                "a2a.agent.role", request.command().agentRole(),
                "a2a.skill.id", request.command().skillId()), () -> {
            try (A2aTelemetryCorrelation ignored = A2aTelemetryCorrelation.open(
                    request.execution(), request.command().messageId())) {
                A2aContracts.SendCommand command = withCurrentTrace(request.command());
                metrics.payload(command.agentRole(), command.skillId(), command.toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                A2aContracts.TaskSnapshot task = metrics.call(command.agentRole(), command.skillId(), "send",
                        () -> await(client.send(command), Duration.ofSeconds(45)));
                associations.record(request.execution(), request.command().messageId(), request.agentCardDigest(),
                        task.taskId(), task.contextId());
                spanLinks.attribute("a2a.task.id", task.taskId());
                spanLinks.attribute("a2a.context.id", task.contextId());
                return task;
            }
        });
    }

    @Override
    public A2aContracts.TaskSnapshot getTask(A2aContracts.TaskQuery query) {
        return metrics.call(query.agentRole(), "none", "get",
                () -> await(client.getTask(query), Duration.ofSeconds(20)));
    }

    @Override
    public A2aContracts.TaskSnapshot cancelTask(A2aContracts.TaskQuery query) {
        return metrics.call(query.agentRole(), "none", "cancel",
                () -> await(client.cancelTask(query), Duration.ofSeconds(30)));
    }

    @Override
    public A2aContracts.TaskSnapshot reconcileDispatch(A2aActivities.DispatchRequest request) {
        requireDispatch(request);
        java.util.Optional<A2aTaskAssociationStore.Association> persisted = associations
                .findByDelegation(request.execution().delegationId());
        if (persisted.isPresent()) {
            metrics.retry(request.command().agentRole(), request.command().skillId());
            metrics.reconciliation(request.command().agentRole(), "association");
            A2aTaskAssociationStore.Association value = persisted.get();
            if (!value.messageId().equals(request.command().messageId())
                    || !value.agentCardDigest().equals(request.agentCardDigest())
                    || !value.agentRole().equals(request.command().agentRole())) {
                throw new SecurityException("Divergent durable A2A dispatch correlation");
            }
            return getTask(new A2aContracts.TaskQuery(value.agentRole(), value.a2aTaskId(), 0));
        }
        java.util.Optional<A2aContracts.TaskSnapshot> discovered = await(client.findTaskByMessageId(
                request.command().agentRole(), request.command().messageId()), Duration.ofSeconds(30));
        if (discovered.isPresent()) {
            metrics.retry(request.command().agentRole(), request.command().skillId());
            metrics.reconciliation(request.command().agentRole(), "remote_task");
            A2aContracts.TaskSnapshot task = discovered.get();
            associations.record(request.execution(), request.command().messageId(), request.agentCardDigest(),
                    task.taskId(), task.contextId());
            return task;
        }
        metrics.reconciliation(request.command().agentRole(), "dispatch");
        return dispatchTask(request);
    }

    @Override
    public A2aContracts.TaskSnapshot continueTask(A2aActivities.ContinuationRequest request) {
        if (request == null || request.execution() == null || request.command() == null
                || request.command().taskId() == null || request.command().contextId() == null) {
            throw new IllegalArgumentException("A2A continuation correlation is incomplete");
        }
        A2aTaskAssociationStore.Association association = associations.findByDelegation(
                        request.execution().delegationId())
                .orElseThrow(() -> new IllegalStateException("A2A continuation has no durable association"));
        if (!association.a2aTaskId().equals(request.command().taskId())
                || !association.a2aContextId().equals(request.command().contextId())
                || !association.agentRole().equals(request.command().agentRole())) {
            throw new SecurityException("A2A continuation changed task correlation");
        }
        A2aContracts.TaskSnapshot result = spanLinks.call("ai.factory.a2a.continue", "temporal-to-a2a", null,
                Map.of("ai_factory.task.id", request.execution().taskId(),
                        "temporal.workflow.id", request.execution().workflowId(),
                        "a2a.message.id", request.command().messageId(),
                        "a2a.task.id", request.command().taskId()), () -> {
                    try (A2aTelemetryCorrelation ignored = A2aTelemetryCorrelation.open(
                            request.execution(), request.command().messageId())) {
                        A2aContracts.SendCommand traced = withCurrentTrace(request.command());
                        metrics.payload(traced.agentRole(), traced.skillId(), traced.toString()
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
                        return metrics.call(traced.agentRole(), traced.skillId(), "continue",
                                () -> await(client.send(traced), Duration.ofSeconds(45)));
                    }
                });
        if (!association.a2aTaskId().equals(result.taskId())
                || !association.a2aContextId().equals(result.contextId())) {
            throw new SecurityException("A2A server forked a continuation into another task");
        }
        return result;
    }

    @Override
    public A2aActivities.ValidatedArtifacts validateArtifacts(A2aActivities.ValidationRequest request) {
        if (request == null || request.task() == null || request.agentRole() == null
                || request.outputContract() == null || request.attemptId() == null) {
            throw new IllegalArgumentException("A2A artifact validation request is incomplete");
        }
        contracts.requirePrimaryOutput(request.agentRole(), request.outputContract());
        if (request.task().state() != A2aContracts.TaskState.COMPLETED || request.task().artifacts().isEmpty()) {
            throw new IllegalArgumentException("Completed A2A task has no final artifact");
        }
        List<A2aActivities.EvidenceReference> references = new ArrayList<>();
        for (A2aContracts.Artifact artifact : request.task().artifacts()) {
            for (A2aContracts.Part part : artifact.parts()) {
                if (!A2aMediaTypes.EVIDENCE_REFERENCE.equals(part.mediaType())) continue;
                Map<String, Object> data = part.data();
                String uri = text(data, "uri");
                String digest = text(data, "digest");
                String contract = text(data, "contract");
                if (!"1".equals(text(data, "schema_version")) || !request.outputContract().equals(contract)
                        || !digest.matches("[0-9a-f]{64}") || part.uri() == null
                        || !uri.equals(part.uri().toString())) {
                    throw new SecurityException("A2A artifact reference is not bound to its business contract");
                }
                A2aEvidenceUriPolicy.requireBound(uri, request.task().taskId(), request.attemptId(), digest);
                references.add(new A2aActivities.EvidenceReference(artifact.artifactId(), uri, digest, contract));
            }
        }
        if (references.isEmpty()) throw new IllegalArgumentException("A2A task lacks an Evidence reference");
        return new A2aActivities.ValidatedArtifacts(request.task().taskId(), references);
    }

    private static <T> T await(CompletionStage<T> stage, Duration timeout) {
        try {
            return stage.toCompletableFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeoutFailure) {
            throw new IllegalStateException("A2A activity dependency timed out", timeoutFailure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("A2A activity interrupted", interrupted);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("A2A activity failed", cause);
        }
    }

    private static void requireDispatch(A2aActivities.DispatchRequest request) {
        if (request == null || request.execution() == null || request.command() == null
                || request.agentCardDigest() == null || !request.agentCardDigest().matches("[0-9a-f]{64}")
                || !request.execution().agentRole().equals(request.command().agentRole())) {
            throw new IllegalArgumentException("A2A dispatch correlation is incomplete");
        }
    }

    private static A2aContracts.SendCommand withCurrentTrace(A2aContracts.SendCommand command) {
        A2aW3cTraceContext trace = A2aW3cTraceContext.capture();
        if (trace == null) return command;
        return new A2aContracts.SendCommand(command.agentRole(), command.skillId(), command.messageId(),
                command.taskId(), command.contextId(), command.parts(), trace.addTo(command.metadata()),
                command.returnImmediately());
    }

    private static String text(Map<String, Object> data, String field) {
        Object value = data.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("A2A artifact reference lacks " + field);
        }
        return text;
    }
}
