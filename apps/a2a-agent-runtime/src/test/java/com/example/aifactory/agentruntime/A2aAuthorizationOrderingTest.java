package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class A2aAuthorizationOrderingTest {

    @Test
    void rejectsUnauthorizedGetCancelAndListBeforeTouchingTheTaskStore() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        A2aTaskStore store = mock(A2aTaskStore.class);
        AgentRuntimeProperties runtime = new AgentRuntimeProperties(
                "developer", URI.create("https://a2a-developer:8090/a2a"));
        A2aSendMessageService service = new A2aSendMessageService(runtime,
                new AgentCardCatalogGenerator(new com.example.aifactory.agentcore.AgentCatalog(), mapper), mapper,
                (taskId, contextId, reason) -> { },
                (submission, envelope) -> new AgentTaskWorkflowStarter.Execution("workflow", "run"), store);
        A2aSendMessageService.Caller wrongRole = new A2aSendMessageService.Caller(
                "client-a", "tenant-a", Set.of("a2a.read", "a2a.cancel", "a2a.role.test-design"));

        assertThatThrownBy(() -> service.getTask(mapper.readTree("{\"id\":\"secret-task\"}"), wrongRole))
                .isInstanceOf(A2aSendMessageService.TaskLookupRejected.class).hasMessage("Task not found");
        assertThatThrownBy(() -> service.cancelTask(mapper.readTree("{\"id\":\"secret-task\"}"), wrongRole))
                .isInstanceOf(A2aSendMessageService.TaskLookupRejected.class).hasMessage("Task not found");
        assertThatThrownBy(() -> service.listTasks(mapper.readTree("{}"), wrongRole))
                .isInstanceOf(A2aSendMessageService.TaskLookupRejected.class).hasMessage("Task not found");
        verifyNoInteractions(store);
    }
}
