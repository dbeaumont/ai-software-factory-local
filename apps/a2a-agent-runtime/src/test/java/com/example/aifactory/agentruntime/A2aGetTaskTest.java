package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Set;

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
                      }
                    }
                  }
                }
                """);
    }
}
