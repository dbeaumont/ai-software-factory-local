package com.example.aifactory.agentruntime;

import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class A2aTckConformanceServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final A2aTckConformanceService service = new A2aTckConformanceService(mapper);

    @Test
    void implementsTheMandatoryTaskLifecycleUsedByTheUpstreamTck() throws Exception {
        Map<String, Object> createdResponse = service.handle("SendMessage", mapper.readTree("""
                {"message":{"role":"ROLE_USER","messageId":"tck-input-required-test",
                "parts":[{"text":"start"}]}}"""));
        Map<String, Object> created = (Map<String, Object>) createdResponse.get("task");

        assertThat(created).containsKeys("id", "contextId", "status");
        assertThat(((Map<?, ?>) created.get("status")).get("state")).isEqualTo("TASK_STATE_INPUT_REQUIRED");
        String id = created.get("id").toString();

        Map<String, Object> completedResponse = service.handle("SendMessage", mapper.readTree("""
                {"message":{"role":"ROLE_USER","messageId":"tck-complete-task-test",
                "taskId":"%s","parts":[{"text":"finish"}]}}""".formatted(id)));
        Map<String, Object> completed = (Map<String, Object>) completedResponse.get("task");
        assertThat(((Map<?, ?>) completed.get("status")).get("state")).isEqualTo("TASK_STATE_COMPLETED");
        assertThat((java.util.List<?>) completed.get("history")).hasSize(2);

        assertThatThrownBy(() -> service.handle("CancelTask", mapper.readTree("{\"id\":\"%s\"}".formatted(id))))
                .isInstanceOf(A2aTckConformanceService.Failure.class)
                .extracting(failure -> ((A2aTckConformanceService.Failure) failure).code)
                .isEqualTo(A2AErrorCodes.TASK_NOT_CANCELABLE);
    }

    @Test
    void mapsUnsupportedMediaAndUnknownTasksToNormativeA2aErrors() throws Exception {
        assertThatThrownBy(() -> service.handle("SendMessage", mapper.readTree("""
                {"message":{"role":"ROLE_USER","messageId":"tck-send-003",
                "parts":[{"raw":"dGNr","mediaType":"application/x-unsupported-tck-type"}]}}""")))
                .isInstanceOf(A2aTckConformanceService.Failure.class)
                .extracting(failure -> ((A2aTckConformanceService.Failure) failure).code)
                .isEqualTo(A2AErrorCodes.CONTENT_TYPE_NOT_SUPPORTED);
        assertThatThrownBy(() -> service.handle("GetTask", mapper.readTree("{\"id\":\"missing\"}")))
                .isInstanceOf(A2aTckConformanceService.Failure.class)
                .extracting(failure -> ((A2aTckConformanceService.Failure) failure).code)
                .isEqualTo(A2AErrorCodes.TASK_NOT_FOUND);
    }
}
