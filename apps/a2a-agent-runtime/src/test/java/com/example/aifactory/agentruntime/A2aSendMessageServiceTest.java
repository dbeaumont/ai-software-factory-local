package com.example.aifactory.agentruntime;

import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aSendMessageServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private A2aSendMessageService service;
    private A2aSecurityProperties unsecured;

    @BeforeEach
    void setUp() {
        AgentRuntimeProperties runtime = new AgentRuntimeProperties(
                "developer", URI.create("http://localhost:8090/a2a"));
        service = new A2aSendMessageService(runtime,
                new AgentCardCatalogGenerator(new com.example.aifactory.agentcore.AgentCatalog(), mapper), mapper);
        unsecured = new A2aSecurityProperties(false, false, null, null, null, java.time.Duration.ofMinutes(5));
    }

    @Test
    void authenticatesAuthorizesValidatesAndDeduplicatesMessageSend() throws Exception {
        JsonNode params = request("message-1", "developer", "a".repeat(64)).path("params");
        A2aSendMessageService.Caller caller = new A2aSendMessageService.Caller("orchestrator", Set.of(
                "a2a.invoke", "a2a.role.developer", "a2a.skill.developer.code-task-v1"));

        A2aSendMessageService.Submission first = service.send(params, caller);
        A2aSendMessageService.Submission replay = service.send(params, caller);

        assertThat(first.taskId()).isEqualTo(replay.taskId());
        assertThat(first.contextId()).isEqualTo(replay.contextId());
        assertThat(first.role()).isEqualTo("developer");
        assertThat(first.skill()).isEqualTo("developer.code-task-v1");

        assertThatThrownBy(() -> service.send(
                request("message-1", "developer", "b".repeat(64)).path("params"), caller))
                .isInstanceOf(A2aSendMessageService.SubmissionRejected.class)
                .hasMessageContaining("collision");
        assertThatThrownBy(() -> service.send(params,
                new A2aSendMessageService.Caller("orchestrator", Set.of("a2a.invoke"))))
                .isInstanceOf(A2aSendMessageService.SubmissionRejected.class)
                .hasMessageContaining("scope");
    }

    @Test
    void appliesCatalogDelegationEdgesOnTheReceivingServer() throws Exception {
        JsonNode params = request("delegated-message", "developer", "a".repeat(64)).path("params");
        Set<String> scopes = Set.of(
                "a2a.invoke", "a2a.role.developer", "a2a.skill.developer.code-task-v1");

        assertThat(service.send(params, new A2aSendMessageService.Caller(
                "ai-factory-agent-code-agent", "tenant-a", "code-agent", scopes)).role())
                .isEqualTo("developer");
        assertThatThrownBy(() -> service.send(
                request("forbidden-delegation", "developer", "b".repeat(64)).path("params"),
                new A2aSendMessageService.Caller(
                        "ai-factory-agent-test-agent", "tenant-a", "test-agent", scopes)))
                .isInstanceOf(A2aSendMessageService.SubmissionRejected.class)
                .hasMessageContaining("cannot delegate");
    }

    @Test
    void continuesInputRequiredOnTheSameTaskAndContextExactlyOnce() throws Exception {
        AgentRuntimeProperties runtime = new AgentRuntimeProperties(
                "developer", URI.create("http://localhost:8090/a2a"));
        InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
        java.util.concurrent.atomic.AtomicInteger continuations = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<String> signaledMessage = new AtomicReference<>();
        AgentTaskWorkflowControl control = new AgentTaskWorkflowControl() {
            @Override public void requestCancellation(String taskId, String contextId, String reason) { }
            @Override public void requestContinuation(String taskId, String contextId, String messageId,
                                                      String envelopeJson) {
                continuations.incrementAndGet();
                signaledMessage.set(messageId);
            }
        };
        A2aSendMessageService resumable = new A2aSendMessageService(runtime,
                new AgentCardCatalogGenerator(new com.example.aifactory.agentcore.AgentCatalog(), mapper), mapper,
                control, (submission, envelope) -> new AgentTaskWorkflowStarter.Execution("workflow", "run"), store);
        A2aSendMessageService.Caller caller = new A2aSendMessageService.Caller("orchestrator", Set.of(
                "a2a.invoke", "a2a.role.developer", "a2a.skill.developer.code-task-v1"));
        A2aSendMessageService.Submission initial = resumable.send(
                request("message-initial", "developer", "a".repeat(64)).path("params"), caller);
        resumable.projectState(initial.taskId(), A2aSendMessageService.TaskState.INPUT_REQUIRED);

        tools.jackson.databind.node.ObjectNode continuation = (tools.jackson.databind.node.ObjectNode) request(
                "message-continuation", "developer", "b".repeat(64)).deepCopy();
        tools.jackson.databind.node.ObjectNode message = (tools.jackson.databind.node.ObjectNode)
                continuation.path("params").path("message");
        message.put("taskId", initial.taskId());
        message.put("contextId", initial.contextId());

        A2aSendMessageService.Submission resumed = resumable.send(continuation.path("params"), caller);
        A2aSendMessageService.Submission replay = resumable.send(continuation.path("params"), caller);

        assertThat(resumed.taskId()).isEqualTo(initial.taskId()).isEqualTo(replay.taskId());
        assertThat(resumed.contextId()).isEqualTo(initial.contextId());
        assertThat(store.find(initial.taskId()).orElseThrow().state())
                .isEqualTo(A2aSendMessageService.TaskState.WORKING);
        assertThat(store.history(initial.taskId(), 10)).extracting(A2aTaskStore.HistoryRecord::messageId)
                .contains("message-continuation");
        assertThat(continuations).hasValue(1);
        assertThat(signaledMessage).hasValue("message-continuation");
    }

    @Test
    void resumesAuthRequiredOnlyWithDedicatedScopeAndNeverAcceptsCredentialMaterial() throws Exception {
        AgentRuntimeProperties runtime = new AgentRuntimeProperties(
                "developer", URI.create("http://localhost:8090/a2a"));
        InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
        java.util.concurrent.atomic.AtomicInteger signals = new java.util.concurrent.atomic.AtomicInteger();
        AgentTaskWorkflowControl control = new AgentTaskWorkflowControl() {
            @Override public void requestCancellation(String taskId, String contextId, String reason) { }
            @Override public void requestContinuation(String taskId, String contextId, String messageId,
                                                      String envelopeJson) { signals.incrementAndGet(); }
        };
        A2aSendMessageService resumable = new A2aSendMessageService(runtime,
                new AgentCardCatalogGenerator(new com.example.aifactory.agentcore.AgentCatalog(), mapper), mapper,
                control, (submission, envelope) -> new AgentTaskWorkflowStarter.Execution("workflow", "run"), store);
        Set<String> baseScopes = Set.of("a2a.invoke", "a2a.role.developer",
                "a2a.skill.developer.code-task-v1");
        A2aSendMessageService.Caller initialCaller = new A2aSendMessageService.Caller("orchestrator", baseScopes);
        A2aSendMessageService.Submission initial = resumable.send(
                request("auth-initial", "developer", "a".repeat(64)).path("params"), initialCaller);
        resumable.projectState(initial.taskId(), A2aSendMessageService.TaskState.AUTH_REQUIRED);

        tools.jackson.databind.node.ObjectNode continuation = continuation(
                "auth-continuation", initial, "b".repeat(64));
        ((tools.jackson.databind.node.ObjectNode) continuation.path("params").path("message").path("metadata"))
                .put("authGrantId", "grant-1");
        assertThatThrownBy(() -> resumable.send(continuation.path("params"), initialCaller))
                .isInstanceOf(A2aSendMessageService.SubmissionRejected.class).hasMessageContaining("scope");

        A2aSendMessageService.Caller authCaller = new A2aSendMessageService.Caller("orchestrator",
                Set.of("a2a.invoke", "a2a.role.developer", "a2a.skill.developer.code-task-v1", "a2a.auth-resume"));
        tools.jackson.databind.node.ObjectNode leaked = continuation(
                "auth-leaked", initial, "c".repeat(64));
        tools.jackson.databind.node.ObjectNode leakedMetadata = (tools.jackson.databind.node.ObjectNode)
                leaked.path("params").path("message").path("metadata");
        leakedMetadata.put("authGrantId", "grant-1");
        leakedMetadata.put("access_token", "must-never-be-persisted");
        assertThatThrownBy(() -> resumable.send(leaked.path("params"), authCaller))
                .isInstanceOf(A2aSendMessageService.SubmissionRejected.class).hasMessageContaining("Credential");

        A2aSendMessageService.Submission resumed = resumable.send(continuation.path("params"), authCaller);
        assertThat(resumed.taskId()).isEqualTo(initial.taskId());
        assertThat(store.find(initial.taskId()).orElseThrow().state())
                .isEqualTo(A2aSendMessageService.TaskState.WORKING);
        assertThat(store.history(initial.taskId(), 10).toString()).doesNotContain("must-never-be-persisted");
        assertThat(signals).hasValue(1);
    }

    private tools.jackson.databind.node.ObjectNode continuation(
            String messageId, A2aSendMessageService.Submission initial, String digest) throws Exception {
        tools.jackson.databind.node.ObjectNode request = (tools.jackson.databind.node.ObjectNode)
                request(messageId, "developer", digest).deepCopy();
        tools.jackson.databind.node.ObjectNode message = (tools.jackson.databind.node.ObjectNode)
                request.path("params").path("message");
        message.put("taskId", initial.taskId());
        message.put("contextId", initial.contextId());
        return request;
    }

    @Test
    void returnsImmediatelyAndRefusesProtocolDowngrade() throws Exception {
        java.util.List<String> auditLines = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.example.aifactory.agentcore.A2aDecisionJournal audit =
                new com.example.aifactory.agentcore.A2aDecisionJournal(
                        java.time.Clock.fixed(java.time.Instant.parse("2026-09-06T12:00:00Z"),
                                java.time.ZoneOffset.UTC),
                        auditLines::add);
        A2aJsonRpcController controller = new A2aJsonRpcController(mapper, service, unsecured, audit);
        byte[] body = mapper.writeValueAsBytes(request("message-2", "developer", "a".repeat(64)));

        UsernamePasswordAuthenticationToken authenticated = new UsernamePasswordAuthenticationToken(
                "orchestrator", "not-serialized", Set.of(
                new SimpleGrantedAuthority("SCOPE_a2a.invoke"),
                new SimpleGrantedAuthority("SCOPE_a2a.role.developer"),
                new SimpleGrantedAuthority("SCOPE_a2a.skill.developer.code-task-v1")));
        Map<String, Object> accepted = controller.handle("1.0", body, authenticated);
        assertThat(accepted).containsKey("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) accepted.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) result.get("status");
        assertThat(status.get("state")).isEqualTo("TASK_STATE_SUBMITTED");

        Map<String, Object> rejected = controller.handle("0.3", body, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) rejected.get("error");
        assertThat(error.get("code")).isEqualTo(A2AErrorCodes.VERSION_NOT_SUPPORTED.code());

        Map<String, Object> unauthenticated = controller.handle("1.0", body, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> authError = (Map<String, Object>) unauthenticated.get("error");
        assertThat(authError.get("message")).isEqualTo("Unauthenticated A2A caller");
        assertThat(errorInfo(authError)).containsEntry("reason", "CALLER_UNAUTHENTICATED")
                .extracting("metadata").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("category", "AUTH").containsEntry("retryable", "false");
        assertThat(auditLines).anyMatch(line -> line.contains("type=AUTHENTICATION outcome=DENIED"));
        assertThat(auditLines).allMatch(line -> !line.contains("orchestrator"));
    }

    @Test
    void classifiesDependencyFailuresWithoutLeakingTheirCause() throws Exception {
        AgentRuntimeProperties runtime = new AgentRuntimeProperties(
                "developer", URI.create("http://localhost:8090/a2a"));
        A2aSendMessageService unavailableService = new A2aSendMessageService(runtime,
                new AgentCardCatalogGenerator(new com.example.aifactory.agentcore.AgentCatalog(), mapper), mapper,
                (taskId, contextId, reason) -> { },
                (submission, envelope) -> { throw new A2aOperationalException(
                        A2aOperationalException.Category.DEPENDENCY, "secret backend detail", null); },
                new InMemoryA2aTaskStore());
        A2aJsonRpcController controller = new A2aJsonRpcController(mapper, unavailableService, unsecured);

        Map<String, Object> response = controller.handle("1.0",
                mapper.writeValueAsBytes(request("message-dependency", "developer", "a".repeat(64))),
                authenticated());

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertThat(error.get("code")).isEqualTo(A2AErrorCodes.INTERNAL.code());
        assertThat(error.get("message")).isEqualTo("A2A dependency is temporarily unavailable");
        assertThat(error.toString()).doesNotContain("secret backend detail");
        assertThat(errorInfo(error)).containsEntry("reason", "DEPENDENCY_UNAVAILABLE")
                .extracting("metadata").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("category", "DEPENDENCY").containsEntry("task_state", "TASK_STATE_FAILED");
    }

    private static UsernamePasswordAuthenticationToken authenticated() {
        return new UsernamePasswordAuthenticationToken("orchestrator", "not-serialized", Set.of(
                new SimpleGrantedAuthority("SCOPE_a2a.invoke"),
                new SimpleGrantedAuthority("SCOPE_a2a.role.developer"),
                new SimpleGrantedAuthority("SCOPE_a2a.skill.developer.code-task-v1")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> errorInfo(Map<String, Object> error) {
        return (Map<String, Object>) ((java.util.List<?>) error.get("data")).getFirst();
    }

    private JsonNode request(String messageId, String role, String digest) throws Exception {
        String json = """
                {
                  "jsonrpc":"2.0",
                  "id":"request-1",
                  "method":"message/send",
                  "params":{
                    "configuration":{"blocking":false},
                    "message":{
                      "role":"ROLE_USER",
                      "messageId":"%s",
                      "parts":[{"kind":"data","data":{
                        "schema_version":"1",
                        "target_role":"%s",
                        "skill_id":"developer.code-task-v1",
                        "input_references":[{"digest":"%s"}]
                      }}],
                      "metadata":{
                        "%s":{
                          "schemaVersion":"1",
                          "taskId":"task-1",
                          "attemptId":"attempt-1",
                          "workflowId":"workflow-1",
                          "workflowRunId":"run-1",
                          "repositoryId":"repository-1",
                          "sourceCommit":"0123456789abcdef0123456789abcdef01234567",
                          "delegationId":"delegation-1",
                          "agentRole":"%s",
                          "inputDigests":["%s"]
                        },
                        "%s":{
                          "traceparent":"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                          "baggage":"task.id=task-1,attempt.id=attempt-1"
                        }
                      }
                    }
                  }
                }
                """.formatted(messageId, role, digest,
                A2aSendMessageService.EXECUTION_CONTEXT_EXTENSION, role, digest, A2aW3cTraceContext.EXTENSION);
        return mapper.readTree(json);
    }
}
