package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aGetTaskTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void returnsOnlyOwnedTaskWithBoundedHistoryAndAuthorizedArtifacts() throws Exception {
        A2aSendMessageService service = service();
        A2aSendMessageService.Caller writer = new A2aSendMessageService.Caller("orchestrator-a", Set.of(
                "a2a.invoke", "a2a.role.developer", "a2a.skill.developer.code-task-v1"));
        A2aSendMessageService.Submission submission = service.send(sendParams(), writer);
        A2aSendMessageService.Caller reader = new A2aSendMessageService.Caller("orchestrator-a", Set.of(
                "a2a.read", "a2a.role.developer"));

        A2aSendMessageService.TaskView visible = service.getTask(
                mapper.readTree("{\"id\":\"" + submission.taskId() + "\",\"historyLength\":1}"), reader);
        assertThat(visible.submission().taskId()).isEqualTo(submission.taskId());
        assertThat(visible.state()).isEqualTo(A2aSendMessageService.TaskState.SUBMITTED);
        assertThat(visible.history()).hasSize(1);
        assertThat(visible.artifacts()).isEmpty();

        assertThatThrownBy(() -> service.getTask(
                mapper.readTree("{\"id\":\"" + submission.taskId() + "\",\"historyLength\":51}"), reader))
                .isInstanceOf(A2aSendMessageService.SubmissionRejected.class);
        assertThatThrownBy(() -> service.getTask(
                mapper.readTree("{\"id\":\"" + submission.taskId() + "\"}"),
                new A2aSendMessageService.Caller("orchestrator-b", reader.scopes())))
                .isInstanceOf(A2aSendMessageService.TaskLookupRejected.class)
                .hasMessage("Task not found");
        assertThatThrownBy(() -> service.getTask(
                mapper.readTree("{\"id\":\"" + submission.taskId() + "\"}"),
                new A2aSendMessageService.Caller("orchestrator-a", Set.of("a2a.read"))))
                .isInstanceOf(A2aSendMessageService.TaskLookupRejected.class)
                .hasMessage("Task not found");
    }

    @Test
    void exposesOrderedStateTransitionsForTemporalReconciliation() throws Exception {
        A2aSendMessageService service = service();
        A2aSendMessageService.Caller writer = new A2aSendMessageService.Caller("orchestrator-a", Set.of(
                "a2a.invoke", "a2a.role.developer", "a2a.skill.developer.code-task-v1"));
        A2aSendMessageService.Submission submission = service.send(sendParams(), writer);
        service.projectState(submission.taskId(), A2aSendMessageService.TaskState.WORKING);
        service.projectState(submission.taskId(), A2aSendMessageService.TaskState.COMPLETED);
        A2aSecurityProperties security = new A2aSecurityProperties(
                false, false, null, null, null, java.time.Duration.ofMinutes(5), false);
        A2aJsonRpcController controller = new A2aJsonRpcController(mapper, service, security);
        String request = """
                {"jsonrpc":"2.0","id":"get-1","method":"GetTask",
                 "params":{"id":"%s","historyLength":50}}
                """.formatted(submission.taskId());
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "orchestrator-a", "not-serialized", Set.of(
                new SimpleGrantedAuthority("SCOPE_a2a.read"),
                new SimpleGrantedAuthority("SCOPE_a2a.role.developer")));

        Map<String, Object> response = controller.handle(
                "1.0", request.getBytes(StandardCharsets.UTF_8), authentication);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) result.get("metadata");
        assertThat(metadata.get("sequence")).isEqualTo(2L);
        List<?> transitions = (List<?>) metadata.get("transitions");
        List<String> states = transitions.stream()
                .map(value -> String.valueOf(((Map<?, ?>) value).get("state"))).toList();
        assertThat(states)
                .containsExactly("TASK_STATE_SUBMITTED", "TASK_STATE_WORKING", "TASK_STATE_COMPLETED");
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) result.get("status");
        assertThat(status.get("timestamp")).isEqualTo(
                ((Map<?, ?>) transitions.getLast()).get("occurredAt"));
    }

    private A2aSendMessageService service() {
        AgentRuntimeProperties runtime = new AgentRuntimeProperties(
                "developer", URI.create("http://localhost:8090/a2a"));
        return new A2aSendMessageService(runtime,
                new AgentCardCatalogGenerator(new com.example.aifactory.agentcore.AgentCatalog(), mapper), mapper);
    }

    private JsonNode sendParams() throws Exception {
        return mapper.readTree("""
                {
                  "configuration":{"blocking":false},
                  "message":{
                    "messageId":"message-get-1",
                    "parts":[{"data":{"target_role":"developer","skill_id":"developer.code-task-v1"}}],
                    "metadata":{
                      "https://ai-factory.local/extensions/execution-context/v1":{
                        "schemaVersion":"1","taskId":"task-1","attemptId":"attempt-1",
                        "workflowId":"workflow-1","workflowRunId":"run-1","repositoryId":"repo-1",
                        "sourceCommit":"0123456789abcdef0123456789abcdef01234567",
                        "delegationId":"delegation-1","agentRole":"developer",
                        "inputDigests":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
                      },
                      "https://ai-factory.local/extensions/w3c-trace-context/v1":{
                        "traceparent":"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                      }
                    }
                  }
                }
                """);
    }
}
