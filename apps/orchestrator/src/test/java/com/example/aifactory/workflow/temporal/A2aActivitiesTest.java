package com.example.aifactory.workflow.temporal;

import com.example.aifactory.a2a.A2aClient;
import com.example.aifactory.a2a.A2aContractMapping;
import com.example.aifactory.a2a.A2aContracts;
import com.example.aifactory.a2a.A2aMediaTypes;
import com.example.aifactory.a2a.A2aExecutionContext;
import com.example.aifactory.a2a.A2aTaskAssociationStore;
import com.example.aifactory.a2a.AgentCardResolver;
import io.temporal.activity.ActivityOptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aActivitiesTest {

    @Test
    void delegatesEachRemoteOperationAndValidatesBoundEvidenceReferences() {
        A2aContracts.TaskSnapshot snapshot = completedTask("patch-proposal-v1", "a".repeat(64));
        AgentCardResolver cards = role -> CompletableFuture.completedFuture(new A2aContracts.AgentCardDescriptor(
                role, URI.create("https://developer.internal/.well-known/agent-card.json"),
                URI.create("https://developer.internal/a2a"), "JSONRPC", "1.0", "b".repeat(64),
                List.of("developer.code-task-v1"), false, true));
        java.util.concurrent.atomic.AtomicInteger sends = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<A2aContracts.SendCommand> sent = new AtomicReference<>();
        A2aClient client = new A2aClient() {
            @Override public java.util.concurrent.CompletionStage<A2aContracts.TaskSnapshot> send(
                    A2aContracts.SendCommand command) {
                sends.incrementAndGet();
                sent.set(command);
                return CompletableFuture.completedFuture(snapshot);
            }
            @Override public java.util.concurrent.CompletionStage<A2aContracts.TaskSnapshot> getTask(
                    A2aContracts.TaskQuery query) { return CompletableFuture.completedFuture(snapshot); }
            @Override public java.util.concurrent.CompletionStage<A2aContracts.TaskSnapshot> cancelTask(
                    A2aContracts.TaskQuery query) { return CompletableFuture.completedFuture(snapshot); }
            @Override public java.util.concurrent.CompletionStage<java.util.Optional<A2aContracts.TaskSnapshot>>
            findTaskByMessageId(String role, String messageId) {
                return CompletableFuture.completedFuture(java.util.Optional.of(snapshot));
            }
        };
        AtomicReference<A2aTaskAssociationStore.Association> persisted = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger preparations = new java.util.concurrent.atomic.AtomicInteger();
        A2aTaskAssociationStore associations = new A2aTaskAssociationStore() {
            @Override public void prepareDelegation(A2aExecutionContext execution, DispatchIntent intent) {
                assertThat(intent).isEqualTo(new DispatchIntent("c".repeat(64), 1_000, 2_000, 3));
                if (preparations.incrementAndGet() == 1) {
                    assertThat(sends).as("delegation must precede the remote side effect").hasValue(0);
                }
            }
            @Override public void record(A2aExecutionContext execution, String messageId, String cardDigest,
                                         String taskId, String contextId) {
                persisted.set(new Association(execution.delegationId(), execution.taskId(), execution.attemptId(),
                        execution.workflowId(), execution.workflowRunId(), execution.sourceCommit(), messageId,
                        execution.agentRole(), cardDigest, taskId, contextId));
            }
            @Override public java.util.Optional<Association> findByDelegation(String delegationId) {
                return java.util.Optional.ofNullable(persisted.get());
            }
            @Override public java.util.Optional<Association> findByMessageId(String role, String messageId) {
                return java.util.Optional.ofNullable(persisted.get());
            }
            @Override public java.util.Optional<Association> findByA2aTaskId(String role, String taskId) {
                return java.util.Optional.ofNullable(persisted.get());
            }
        };
        A2aActivitiesImpl activities = new A2aActivitiesImpl(
                cards, client, new A2aContractMapping(new ObjectMapper()), associations);

        assertThat(activities.resolveAgent("developer").agentRole()).isEqualTo("developer");
        assertThat(activities.dispatchTask(new A2aActivities.DispatchRequest(
                execution(), "b".repeat(64), command())).taskId()).isEqualTo("task-1");
        assertThat(persisted.get()).satisfies(correlation -> {
            assertThat(correlation.workflowId()).isEqualTo("workflow-1");
            assertThat(correlation.workflowRunId()).isEqualTo("run-1");
            assertThat(correlation.messageId()).isEqualTo("message-1");
            assertThat(correlation.agentCardDigest()).isEqualTo("b".repeat(64));
        });
        assertThat(sent.get().metadata()).extractingByKey(
                        "https://ai-factory.local/extensions/w3c-trace-context/v1")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .extractingByKey("traceparent")
                .asString()
                .matches("00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}");
        assertThat(activities.reconcileDispatch(new A2aActivities.DispatchRequest(
                execution(), "b".repeat(64), command()))).isEqualTo(snapshot);
        assertThat(sends).hasValue(1);
        A2aContracts.SendCommand continuation = new A2aContracts.SendCommand(
                "developer", "developer.code-task-v1", "message-2", "task-1", "context-1",
                command().parts(), Map.of("continuationSequence", 1), true);
        assertThat(activities.continueTask(new A2aActivities.ContinuationRequest(execution(), continuation)))
                .isEqualTo(snapshot);
        assertThat(sends).hasValue(2);
        assertThat(activities.getTask(new A2aContracts.TaskQuery("developer", "task-1", 10))).isEqualTo(snapshot);
        assertThat(activities.cancelTask(new A2aContracts.TaskQuery("developer", "task-1", 10))).isEqualTo(snapshot);
        assertThat(activities.validateArtifacts(new A2aActivities.ValidationRequest(
                "developer", "patch-proposal-v1", "attempt-1", snapshot)).references()).singleElement()
                .satisfies(reference -> assertThat(reference.uri()).startsWith("evidence://task-1/"));

        assertThatThrownBy(() -> activities.validateArtifacts(new A2aActivities.ValidationRequest(
                "developer", "security-assessment-v1", "attempt-1", snapshot)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eachA2aOperationHasAnExplicitRetryAndTimeoutPolicy() {
        ActivityOptions resolve = policy(TemporalActivityPolicies.Kind.A2A_RESOLVE);
        ActivityOptions dispatch = policy(TemporalActivityPolicies.Kind.A2A_DISPATCH);
        ActivityOptions get = policy(TemporalActivityPolicies.Kind.A2A_GET);
        ActivityOptions cancel = policy(TemporalActivityPolicies.Kind.A2A_CANCEL);
        ActivityOptions validate = policy(TemporalActivityPolicies.Kind.A2A_VALIDATE);
        ActivityOptions reconcile = policy(TemporalActivityPolicies.Kind.A2A_RECONCILE);
        ActivityOptions continuation = policy(TemporalActivityPolicies.Kind.A2A_CONTINUE);

        assertThat(resolve.getRetryOptions().getMaximumAttempts()).isEqualTo(3);
        assertThat(dispatch.getRetryOptions().getMaximumAttempts()).isEqualTo(1);
        assertThat(get.getRetryOptions().getMaximumAttempts()).isEqualTo(3);
        assertThat(cancel.getRetryOptions().getMaximumAttempts()).isEqualTo(2);
        assertThat(validate.getRetryOptions().getMaximumAttempts()).isEqualTo(1);
        assertThat(reconcile.getRetryOptions().getMaximumAttempts()).isEqualTo(3);
        assertThat(continuation.getRetryOptions().getMaximumAttempts()).isEqualTo(1);
        assertThat(List.of(resolve.getStartToCloseTimeout(), dispatch.getStartToCloseTimeout(),
                get.getStartToCloseTimeout(), cancel.getStartToCloseTimeout(), validate.getStartToCloseTimeout(),
                continuation.getStartToCloseTimeout()))
                .doesNotContainNull();
    }

    private static ActivityOptions policy(TemporalActivityPolicies.Kind kind) {
        return TemporalActivityPolicies.forKind(kind);
    }

    private static A2aContracts.SendCommand command() {
        return new A2aContracts.SendCommand("developer", "developer.code-task-v1", "message-1", null, null,
                List.of(new A2aContracts.Part(A2aMediaTypes.JSON, null,
                        Map.of("instruction", "change", "budget", Map.of(
                                "max_tokens", 1_000, "max_cost_micros", 2_000, "max_turns", 3)), null)),
                Map.of(), true);
    }

    private static A2aExecutionContext execution() {
        return new A2aExecutionContext("1", "task-1", "attempt-1", "workflow-1", "run-1", "customer-api",
                "a".repeat(40), "delegation-1", null, "developer", List.of("c".repeat(64)));
    }

    private static A2aContracts.TaskSnapshot completedTask(String contract, String digest) {
        String uri = "evidence://task-1/attempt-1/agent-result/" + digest;
        Map<String, Object> data = Map.of(
                "schema_version", "1", "reference_id", "artifact-1", "uri", uri, "digest", digest,
                "size_bytes", 123, "media_type", "application/json", "classification", "INTERNAL",
                "contract", contract, "contract_version", "1");
        A2aContracts.Part part = new A2aContracts.Part(
                A2aMediaTypes.EVIDENCE_REFERENCE, null, data, URI.create(uri));
        return new A2aContracts.TaskSnapshot("task-1", "context-1", A2aContracts.TaskState.COMPLETED,
                Instant.parse("2026-09-06T12:00:00Z"),
                List.of(new A2aContracts.Artifact("artifact-1", "developer-result", List.of(part), Map.of())),
                Map.of());
    }
}
