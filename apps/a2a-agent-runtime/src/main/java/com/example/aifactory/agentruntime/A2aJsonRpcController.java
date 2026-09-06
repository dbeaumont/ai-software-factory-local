package com.example.aifactory.agentruntime;

import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Thin JSON-RPC 2.0 transport adapter; SDK and HTTP concerns do not leak into the execution core. */
@RestController
final class A2aJsonRpcController {
    static final String ENDPOINT = "/a2a";
    static final int MAX_REQUEST_BYTES = 1_048_576;

    private final ObjectMapper mapper;
    private final A2aSendMessageService service;
    private final A2aSecurityProperties security;

    A2aJsonRpcController(ObjectMapper mapper, A2aSendMessageService service, A2aSecurityProperties security) {
        this.mapper = mapper;
        this.service = service;
        this.security = security;
    }

    @PostMapping(path = ENDPOINT, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    Map<String, Object> handle(
            @RequestHeader(name = "A2A-Version", required = false) String version,
            @RequestBody byte[] body,
            Authentication authentication) {
        Object requestId = null;
        try {
            if (!"1.0".equals(version)) {
                throw new RpcFailure(A2AErrorCodes.VERSION_NOT_SUPPORTED, "A2A-Version 1.0 is required");
            }
            if (body == null || body.length == 0 || body.length > MAX_REQUEST_BYTES) {
                throw new RpcFailure(A2AErrorCodes.INVALID_REQUEST, "JSON-RPC request size is invalid");
            }
            JsonNode request = mapper.readTree(body);
            requestId = request.has("id") ? mapper.treeToValue(request.get("id"), Object.class) : null;
            if (!"2.0".equals(request.path("jsonrpc").asText())) {
                throw new RpcFailure(A2AErrorCodes.INVALID_REQUEST, "JSON-RPC version 2.0 is required");
            }
            return switch (request.path("method").asText()) {
                case "message/send" -> response(requestId,
                        task(service.send(request.path("params"), caller(authentication)),
                                A2aSendMessageService.TaskState.SUBMITTED, List.of(), List.of()));
                case "tasks/get" -> {
                    A2aSendMessageService.TaskView view = service.getTask(
                            request.path("params"), caller(authentication));
                    yield response(requestId, task(view.submission(), view.state(), view.history(), view.artifacts()));
                }
                case "tasks/list" -> {
                    A2aSendMessageService.TaskPage page = service.listTasks(
                            request.path("params"), caller(authentication));
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("tasks", page.tasks().stream()
                            .map(view -> task(view.submission(), view.state(), view.history(), view.artifacts())).toList());
                    if (page.nextPageToken() != null) result.put("nextPageToken", page.nextPageToken());
                    yield response(requestId, Map.copyOf(result));
                }
                case "tasks/cancel" -> {
                    A2aSendMessageService.TaskView view = service.cancelTask(
                            request.path("params"), caller(authentication));
                    yield response(requestId, task(view.submission(), view.state(), view.history(), view.artifacts()));
                }
                default -> throw new RpcFailure(A2AErrorCodes.METHOD_NOT_FOUND, "A2A method is not available");
            };
        } catch (RpcFailure failure) {
            return error(requestId, failure.code, failure.getMessage());
        } catch (A2aSendMessageService.SubmissionRejected failure) {
            return error(requestId, A2AErrorCodes.INVALID_PARAMS, failure.getMessage());
        } catch (A2aSendMessageService.TaskLookupRejected failure) {
            return error(requestId, A2AErrorCodes.TASK_NOT_FOUND, failure.getMessage());
        } catch (A2aSendMessageService.TaskNotCancelable failure) {
            return error(requestId, A2AErrorCodes.TASK_NOT_CANCELABLE, failure.getMessage());
        } catch (Exception failure) {
            return error(requestId, A2AErrorCodes.JSON_PARSE, "Invalid JSON-RPC request");
        }
    }

    private A2aSendMessageService.Caller caller(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new A2aSendMessageService.SubmissionRejected("Unauthenticated A2A caller");
        }
        Set<String> scopes = authentication.getAuthorities().stream().map(authority -> authority.getAuthority())
                .map(value -> value.startsWith("SCOPE_") ? value.substring("SCOPE_".length()) : value)
                .collect(Collectors.toUnmodifiableSet());
        String tenantId = authentication instanceof JwtAuthenticationToken jwt
                ? jwt.getToken().getClaimAsString("tenant_id") : authentication.getName();
        return new A2aSendMessageService.Caller(authentication.getName(), tenantId, scopes);
    }

    private static Map<String, Object> response(Object id, Map<String, Object> task) {
        return Map.of("jsonrpc", "2.0", "id", id == null ? "null" : id, "result", task);
    }

    private static Map<String, Object> task(
            A2aSendMessageService.Submission submission,
            A2aSendMessageService.TaskState state,
            List<A2aSendMessageService.HistoryItem> history,
            List<Map<String, Object>> artifacts) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("kind", "task");
        task.put("id", submission.taskId());
        task.put("contextId", submission.contextId());
        task.put("status", Map.of("state", "TASK_STATE_" + state.name(), "timestamp", submission.submittedAt().toString()));
        task.put("artifacts", List.copyOf(artifacts));
        task.put("history", history.stream().map(item -> Map.of(
                "kind", "message",
                "role", "ROLE_USER",
                "messageId", item.messageId(),
                "parts", List.of(Map.of("kind", "text", "text", item.event())),
                "metadata", Map.of("occurredAt", item.occurredAt().toString()))).toList());
        task.put("metadata", Map.of(
                "messageId", submission.messageId(),
                "delegationId", submission.delegationId(),
                "agentRole", submission.role(),
                "skillId", submission.skill()));
        return Map.copyOf(task);
    }

    private static Map<String, Object> error(Object id, A2AErrorCodes code, String message) {
        return Map.of("jsonrpc", "2.0", "id", id == null ? "null" : id,
                "error", Map.of("code", code.code(), "message", message));
    }

    private static final class RpcFailure extends RuntimeException {
        private final A2AErrorCodes code;
        RpcFailure(A2AErrorCodes code, String message) { super(message); this.code = code; }
    }
}
