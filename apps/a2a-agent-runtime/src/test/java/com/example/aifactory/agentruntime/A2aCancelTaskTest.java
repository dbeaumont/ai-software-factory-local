package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aCancelTaskTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void signalsWorkflowOnceAndMakesCancellationIdempotent() throws Exception {
        AtomicInteger signals = new AtomicInteger();
        A2aSendMessageService service = service((taskId, contextId, reason) -> signals.incrementAndGet());
        A2aSendMessageService.Caller caller = caller();
        A2aSendMessageService.Submission submission = service.send(send("message-cancel-1"), caller);
        tools.jackson.databind.JsonNode query = mapper.readTree("{\"id\":\"" + submission.taskId() + "\"}");

        assertThat(service.cancelTask(query, caller).state()).isEqualTo(A2aSendMessageService.TaskState.CANCELED);
        assertThat(service.cancelTask(query, caller).state()).isEqualTo(A2aSendMessageService.TaskState.CANCELED);
        assertThat(signals).hasValue(1);
    }

    @Test
    void refusesTerminalTaskAndMasksUnauthorizedTask() throws Exception {
        A2aSendMessageService service = service((taskId, contextId, reason) -> { });
        A2aSendMessageService.Caller caller = caller();
        A2aSendMessageService.Submission submission = service.send(send("message-terminal-1"), caller);
        tools.jackson.databind.JsonNode query = mapper.readTree("{\"id\":\"" + submission.taskId() + "\"}");
        service.projectState(submission.taskId(), A2aSendMessageService.TaskState.COMPLETED);

        assertThatThrownBy(() -> service.cancelTask(query, caller))
                .isInstanceOf(A2aSendMessageService.TaskNotCancelable.class);
        assertThatThrownBy(() -> service.cancelTask(query,
                new A2aSendMessageService.Caller("other", "other-tenant", caller.scopes())))
                .isInstanceOf(A2aSendMessageService.TaskLookupRejected.class)
                .hasMessage("Task not found");
    }

    private A2aSendMessageService service(AgentTaskWorkflowControl control) {
        AgentRuntimeProperties runtime = new AgentRuntimeProperties(
                "developer", URI.create("http://localhost:8090/a2a"));
        return new A2aSendMessageService(runtime,
                new AgentCardCatalogGenerator(new com.example.aifactory.agentcore.AgentCatalog(), mapper),
                mapper, control);
    }

    private static A2aSendMessageService.Caller caller() {
        return new A2aSendMessageService.Caller("orchestrator", "tenant-a", Set.of(
                "a2a.invoke", "a2a.cancel", "a2a.role.developer", "a2a.skill.developer.code-task-v1"));
    }

    private tools.jackson.databind.JsonNode send(String messageId) throws Exception {
        return mapper.readTree("""
                {"configuration":{"blocking":false},"message":{
                  "messageId":"%s",
                  "parts":[{"data":{"target_role":"developer","skill_id":"developer.code-task-v1"}}],
                  "metadata":{"https://ai-factory.local/extensions/execution-context/v1":{
                    "schemaVersion":"1","taskId":"task-1","attemptId":"attempt-1",
                    "workflowId":"workflow-1","workflowRunId":"run-1","repositoryId":"repo-1",
                    "sourceCommit":"0123456789abcdef0123456789abcdef01234567",
                    "delegationId":"delegation-1","agentRole":"developer",
                    "inputDigests":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
                  }}}
                }
                """.formatted(messageId));
    }
}
