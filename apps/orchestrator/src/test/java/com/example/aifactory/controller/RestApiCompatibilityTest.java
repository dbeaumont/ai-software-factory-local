package com.example.aifactory.controller;

import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RestApiCompatibilityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesTheVersion02TaskAndCapabilityRoutes() throws Exception {
        assertThat(TaskController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/api/tasks");
        assertRoute(TaskController.class.getMethod("create", TaskRequest.class), PostMapping.class, "", true);
        assertRoute(TaskController.class.getMethod("list"), GetMapping.class, "", false);
        assertRoute(TaskController.class.getMethod("get", String.class), GetMapping.class, "/{id}", false);
        assertRoute(TaskController.class.getMethod("approve", String.class), PostMapping.class, "/{id}/approve", true);

        assertThat(FactoryController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/api");
        assertRoute(FactoryController.class.getMethod("capabilities"), GetMapping.class, "/capabilities", false);
    }

    @Test
    void preservesRequestAndTaskViewJsonFields() throws Exception {
        TaskRequest request = mapper.readValue("""
                {"repositoryUrl":"https://example.test/repo.git","baseBranch":"main",
                 "requirement":"change","llmMode":"CLOUD"}
                """, TaskRequest.class);
        JsonNode response = mapper.valueToTree(new TaskState("task-1", "AF-0001", request).view());

        assertThat(response.propertyNames()).containsAll(Set.of(
                "id", "ticketNumber", "status", "repositoryUrl", "baseBranch", "requirement", "llmMode",
                "workspace", "sourceCommit", "model", "plan", "patch", "testSummary", "qualitySummary",
                "securitySummary", "review", "pullRequestUrl", "error", "steps", "createdAt", "updatedAt",
                "executionMode", "workflowRunId", "dagVersion", "globalBudget"));
        assertThat(response.path("executionMode").asText()).isEqualTo("PIPELINE");
        assertThat(response.path("dagVersion").asText()).isEqualTo("pipeline-v1");
        assertThat(response.path("globalBudget").propertyNames()).containsAll(Set.of(
                "maxTokens", "maxCostMicros", "maxTurns", "usedTokens", "usedCostMicros", "usedTurns"));
    }

    private static void assertRoute(Method method, Class<?> annotationType, String path, boolean accepted) {
        String[] paths = annotationType == GetMapping.class
                ? method.getAnnotation(GetMapping.class).value()
                : method.getAnnotation(PostMapping.class).value();
        if (path.isEmpty()) assertThat(paths).isEmpty();
        else assertThat(paths).containsExactly(path);
        ResponseStatus status = method.getAnnotation(ResponseStatus.class);
        if (accepted) assertThat(status.value()).isEqualTo(HttpStatus.ACCEPTED);
        else assertThat(status).isNull();
    }
}
