package com.example.aifactory.agentruntime;

import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
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
            if (!"2.0".equals(request.path("jsonrpc").asText())
                    || !"message/send".equals(request.path("method").asText())) {
                throw new RpcFailure(A2AErrorCodes.METHOD_NOT_FOUND, "Only message/send is available");
            }
            A2aSendMessageService.Submission submission = service.send(
                    request.path("params"), caller(authentication));
            return response(requestId, submission);
        } catch (RpcFailure failure) {
            return error(requestId, failure.code, failure.getMessage());
        } catch (A2aSendMessageService.SubmissionRejected failure) {
            return error(requestId, A2AErrorCodes.INVALID_PARAMS, failure.getMessage());
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
        return new A2aSendMessageService.Caller(authentication.getName(), scopes);
    }

    private static Map<String, Object> response(Object id, A2aSendMessageService.Submission submission) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("kind", "task");
        task.put("id", submission.taskId());
        task.put("contextId", submission.contextId());
        task.put("status", Map.of("state", "TASK_STATE_SUBMITTED", "timestamp", submission.submittedAt().toString()));
        task.put("artifacts", List.of());
        task.put("history", List.of());
        task.put("metadata", Map.of(
                "messageId", submission.messageId(),
                "delegationId", submission.delegationId(),
                "agentRole", submission.role(),
                "skillId", submission.skill()));
        return Map.of("jsonrpc", "2.0", "id", id == null ? "null" : id, "result", task);
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
