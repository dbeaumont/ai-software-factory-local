package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aListTasksTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void filtersBeforeOpaquePaginationAndNeverEnumeratesAnotherTenant() throws Exception {
        A2aSendMessageService service = service();
        A2aSendMessageService.Caller tenantA = caller("tenant-a");
        A2aSendMessageService.Caller tenantB = caller("tenant-b");
        service.send(send("message-a1", "delegation-a1"), tenantA);
        service.send(send("message-a2", "delegation-a2"), tenantA);
        service.send(send("message-b1", "delegation-b1"), tenantB);

        A2aSendMessageService.TaskPage first = service.listTasks(
                mapper.readTree("{\"pageSize\":1,\"status\":\"TASK_STATE_SUBMITTED\"}"), tenantA);
        assertThat(first.tasks()).hasSize(1);
        assertThat(first.nextPageToken()).isNotBlank();
        assertThatThrownBy(() -> service.listTasks(mapper.readTree(
                "{\"pageSize\":1,\"status\":\"TASK_STATE_SUBMITTED\",\"pageToken\":\""
                        + first.nextPageToken() + "\"}"), tenantB))
                .isInstanceOf(A2aSendMessageService.SubmissionRejected.class)
                .hasMessageContaining("pageToken");
        A2aSendMessageService.TaskPage second = service.listTasks(mapper.readTree(
                "{\"pageSize\":1,\"status\":\"TASK_STATE_SUBMITTED\",\"pageToken\":\""
                        + first.nextPageToken() + "\"}"), tenantA);
        assertThat(second.tasks()).hasSize(1);
        assertThat(second.nextPageToken()).isNull();
        assertThat(service.listTasks(mapper.readTree("{\"pageSize\":10}"), tenantB).tasks()).hasSize(1);

        assertThatThrownBy(() -> service.listTasks(mapper.readTree("{\"pageSize\":101}"), tenantA))
                .isInstanceOf(A2aSendMessageService.SubmissionRejected.class)
                .hasMessageContaining("pageSize");
    }

    private A2aSendMessageService service() {
        AgentRuntimeProperties runtime = new AgentRuntimeProperties(
                "developer", URI.create("http://localhost:8090/a2a"));
        return new A2aSendMessageService(runtime,
                new AgentCardCatalogGenerator(new com.example.aifactory.agentcore.AgentCatalog(), mapper), mapper);
    }

    private static A2aSendMessageService.Caller caller(String tenant) {
        return new A2aSendMessageService.Caller("orchestrator", tenant,
                Set.of("a2a.invoke", "a2a.read", "a2a.role.developer",
                        "a2a.skill.developer.code-task-v1"));
    }

    private tools.jackson.databind.JsonNode send(String messageId, String delegationId) throws Exception {
        return mapper.readTree("""
                {"configuration":{"blocking":false},"message":{
                  "messageId":"%s",
                  "parts":[{"data":{"target_role":"developer","skill_id":"developer.code-task-v1"}}],
                  "metadata":{"https://ai-factory.local/extensions/execution-context/v1":{
                    "schemaVersion":"1","taskId":"task-1","attemptId":"attempt-1",
                    "workflowId":"workflow-1","workflowRunId":"run-1","repositoryId":"repo-1",
                    "sourceCommit":"0123456789abcdef0123456789abcdef01234567",
                    "delegationId":"%s","agentRole":"developer",
                    "inputDigests":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
                  },"https://ai-factory.local/extensions/w3c-trace-context/v1":{
                    "traceparent":"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
                  }}}
                }
                """.formatted(messageId, delegationId));
    }
}
