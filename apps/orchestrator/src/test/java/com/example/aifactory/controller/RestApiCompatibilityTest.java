package com.example.aifactory.controller;

import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskCancellationRequest;
import com.example.aifactory.model.HumanDecisionResponse;
import com.example.aifactory.model.ManifestApprovalRequest;
import com.example.aifactory.model.OperatorActionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestApiCompatibilityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesTheVersion02TaskAndCapabilityRoutes() throws Exception {
        assertThat(TaskController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/api/tasks");
        assertRoute(TaskController.class.getMethod("create", TaskRequest.class), PostMapping.class, "", true);
        assertRoute(TaskController.class.getMethod("list"), GetMapping.class, "", false);
        assertRoute(TaskController.class.getMethod("get", String.class), GetMapping.class, "/{id}", false);
        assertRoute(TaskController.class.getMethod("approve", String.class), PostMapping.class, "/{id}/approve", true);
        assertRoute(TaskController.class.getMethod("approveManifest", String.class, ManifestApprovalRequest.class),
                PostMapping.class, "/{id}/approve-manifest", true);
        assertRoute(TaskController.class.getMethod("cancel", String.class, TaskCancellationRequest.class),
                PostMapping.class, "/{id}/cancel", true);
        assertRoute(TaskController.class.getMethod("answerDecision", String.class, String.class,
                HumanDecisionResponse.class), PostMapping.class, "/{id}/decisions/{requestId}", true);
        assertRoute(TaskController.class.getMethod("retryDelegation", String.class, String.class,
                OperatorActionRequest.class), PostMapping.class, "/{id}/delegations/{delegationId}/retry", true);
        assertThat(FactoryController.class.getAnnotation(RequestMapping.class).value()).containsExactly("/api");
        assertRoute(FactoryController.class.getMethod("capabilities"), GetMapping.class, "/capabilities", false);
    }

    @Test
    void preservesRequestAndTaskViewJsonFields() throws Exception {
        TaskRequest request = mapper.readValue("""
                {"repositoryUrl":"https://example.test/repo.git","baseBranch":"main",
                 "requirement":"change","llmMode":"CLOUD","routingFacts":{
                   "qualification":"QUALIFIED","risk":"R1","modules":1,"domains":1,
                   "estimatedFiles":1,"independentCodeScopes":1,"impacts":[],
                   "materialDecisionOpen":false,"inputsComplete":true,"contradictory":false,
                   "budgetAvailable":true}}
                """, TaskRequest.class);
        JsonNode response = mapper.valueToTree(new TaskState("task-1", "AF-0001", request).view());

        assertThat(response.propertyNames()).containsAll(Set.of(
                "id", "ticketNumber", "status", "repositoryUrl", "baseBranch", "requirement", "llmMode",
                "routingFacts",
                "workspace", "sourceCommit", "model", "plan", "patch", "testSummary", "qualitySummary",
                "securitySummary", "review", "pullRequestUrl", "error", "steps", "createdAt", "updatedAt",
                "workflowRunId", "dagVersion", "globalBudget"));
        assertThat(response.propertyNames()).contains(
                "delegations", "artifacts", "contradictions", "decisions", "humanActions");
        assertThat(response.has("executionMode")).isFalse();
        assertThat(response.path("dagVersion").asText()).isEqualTo("hierarchical-v2");
        assertThat(response.path("globalBudget").propertyNames()).containsAll(Set.of(
                "maxTokens", "maxCostMicros", "maxTurns", "usedTokens", "usedCostMicros", "usedTurns"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PIPELINE", "HIERARCHICAL_SHADOW", "HIERARCHICAL_CANARY"})
    void rejectsLegacyExecutionModesAtThePublicAdmissionBoundary(String mode) {
        String payload = """
                {"repositoryUrl":"https://example.test/repo.git","baseBranch":"main",
                 "requirement":"change","llmMode":"CLOUD","executionMode":"%s",
                 "routingFacts":{"qualification":"QUALIFIED","risk":"R1","modules":1,"domains":1,
                 "estimatedFiles":1,"independentCodeScopes":1,"impacts":[],"materialDecisionOpen":false,
                 "inputsComplete":true,"contradictory":false,"budgetAvailable":true}}
                """.formatted(mode);

        ObjectMapper strictMapper = tools.jackson.databind.json.JsonMapper.builder()
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        assertThatThrownBy(() -> strictMapper.readValue(payload, TaskRequest.class))
                .isInstanceOf(tools.jackson.databind.exc.UnrecognizedPropertyException.class);
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
