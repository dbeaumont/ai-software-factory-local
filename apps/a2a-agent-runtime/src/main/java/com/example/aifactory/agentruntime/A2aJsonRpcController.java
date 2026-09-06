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
    reactor.core.publisher.Mono<Map<String, Object>> handleAsync(
            @RequestHeader(name = "A2A-Version", required = false) String version,
            @RequestBody byte[] body,
            Authentication authentication) {
        return reactor.core.publisher.Mono.fromCallable(() -> handle(version, body, authentication))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    Map<String, Object> handle(String version, byte[] body, Authentication authentication) {
        Object requestId = null;
        try {
            if (!"1.0".equals(version)) {
                throw new RpcFailure(A2AErrorCodes.VERSION_NOT_SUPPORTED, "A2A-Version 1.0 is required");
            }
            if (body == null || body.length == 0 || body.length > MAX_REQUEST_BYTES) {
                throw new RpcFailure(A2AErrorCodes.INVALID_REQUEST, "JSON-RPC request size is invalid");
            }
            JsonNode request;
            try {
                request = mapper.readTree(body);
            } catch (Exception malformed) {
                throw new RpcFailure(A2AErrorCodes.JSON_PARSE, "Invalid JSON-RPC document");
            }
            requestId = request.has("id") ? mapper.treeToValue(request.get("id"), Object.class) : null;
            if (!"2.0".equals(request.path("jsonrpc").asText())) {
                throw new RpcFailure(A2AErrorCodes.INVALID_REQUEST, "JSON-RPC version 2.0 is required");
            }
            return switch (request.path("method").asText()) {
                case "message/send" -> response(requestId,
                        task(service.send(request.path("params"), caller(authentication)),
                                A2aSendMessageService.TaskState.SUBMITTED, 0, List.of(), List.of()));
                case "tasks/get" -> {
                    A2aSendMessageService.TaskView view = service.getTask(
                            request.path("params"), caller(authentication));
                    yield response(requestId, task(view.submission(), view.state(), view.sequence(),
                            view.history(), view.artifacts()));
                }
                case "tasks/list" -> {
                    A2aSendMessageService.TaskPage page = service.listTasks(
                            request.path("params"), caller(authentication));
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("tasks", page.tasks().stream()
                            .map(view -> task(view.submission(), view.state(), view.sequence(),
                                    view.history(), view.artifacts())).toList());
                    if (page.nextPageToken() != null) result.put("nextPageToken", page.nextPageToken());
                    yield response(requestId, Map.copyOf(result));
                }
                case "tasks/cancel" -> {
                    A2aSendMessageService.TaskView view = service.cancelTask(
                            request.path("params"), caller(authentication));
                    yield response(requestId, task(view.submission(), view.state(), view.sequence(),
                            view.history(), view.artifacts()));
                }
                default -> throw new RpcFailure(A2AErrorCodes.METHOD_NOT_FOUND, "A2A method is not available");
            };
        } catch (RpcFailure failure) {
            return error(requestId, failure.code, failure.getMessage(), classify(failure.code, failure.getMessage()));
        } catch (A2aSendMessageService.SubmissionRejected failure) {
            return error(requestId, A2AErrorCodes.INVALID_PARAMS, failure.getMessage(), classifySubmission(failure));
        } catch (A2aSendMessageService.TaskLookupRejected failure) {
            return error(requestId, A2AErrorCodes.TASK_NOT_FOUND, failure.getMessage(),
                    new FailureInfo(5, "TASK_NOT_FOUND", "LOOKUP", false, null));
        } catch (A2aSendMessageService.TaskNotCancelable failure) {
            return error(requestId, A2AErrorCodes.TASK_NOT_CANCELABLE, failure.getMessage(),
                    new FailureInfo(9, "TASK_NOT_CANCELABLE", "BUSINESS", false, "REJECTED"));
        } catch (A2aOperationalException failure) {
            FailureInfo info = switch (failure.category()) {
                case TIMEOUT -> new FailureInfo(4, "DEPENDENCY_TIMEOUT", "TIMEOUT", true, "FAILED");
                case QUOTA -> new FailureInfo(8, "QUOTA_EXCEEDED", "QUOTA", true, "FAILED");
                case DEPENDENCY -> new FailureInfo(14, "DEPENDENCY_UNAVAILABLE", "DEPENDENCY", true, "FAILED");
            };
            String message = switch (failure.category()) {
                case TIMEOUT -> "A2A dependency timed out";
                case QUOTA -> "A2A quota exceeded";
                case DEPENDENCY -> "A2A dependency is temporarily unavailable";
            };
            return error(requestId, A2AErrorCodes.INTERNAL, message, info);
        } catch (org.springframework.dao.TransientDataAccessException
                 | A2aPushNotificationSender.NotificationDeliveryException failure) {
            return error(requestId, A2AErrorCodes.INTERNAL, "A2A dependency is temporarily unavailable",
                    new FailureInfo(14, "DEPENDENCY_UNAVAILABLE", "DEPENDENCY", true, "FAILED"));
        } catch (SecurityException failure) {
            return error(requestId, A2AErrorCodes.INVALID_PARAMS, "A2A operation is not authorized",
                    new FailureInfo(7, "POLICY_DENIED", "AUTH", false, "REJECTED"));
        } catch (IllegalArgumentException failure) {
            return error(requestId, A2AErrorCodes.INVALID_PARAMS, "A2A contract validation failed",
                    new FailureInfo(3, "CONTRACT_INVALID", "CONTRACT", false, "REJECTED"));
        } catch (Exception failure) {
            return error(requestId, A2AErrorCodes.INTERNAL, "Internal A2A processing failure",
                    new FailureInfo(13, "INTERNAL_FAILURE", "INTERNAL", true, "FAILED"));
        }
    }

    private A2aSendMessageService.Caller caller(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new A2aSendMessageService.SubmissionRejected("Unauthenticated A2A caller");
        }
        Set<String> scopes = authentication.getAuthorities().stream().map(authority -> authority.getAuthority())
                .map(value -> value.startsWith("SCOPE_") ? value.substring("SCOPE_".length()) : value)
                .collect(Collectors.toUnmodifiableSet());
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String clientId = jwt.getToken().getClaimAsString("client_id");
            String tenantId = jwt.getToken().getClaimAsString("tenant_id");
            if (clientId == null || clientId.isBlank() || tenantId == null || tenantId.isBlank()) {
                throw new A2aSendMessageService.SubmissionRejected(
                        "Authenticated A2A caller lacks client or tenant binding");
            }
            return new A2aSendMessageService.Caller(clientId, tenantId, scopes);
        }
        return new A2aSendMessageService.Caller(authentication.getName(), authentication.getName(), scopes);
    }

    private static Map<String, Object> response(Object id, Map<String, Object> task) {
        return Map.of("jsonrpc", "2.0", "id", id == null ? "null" : id, "result", task);
    }

    private static Map<String, Object> task(
            A2aSendMessageService.Submission submission,
            A2aSendMessageService.TaskState state,
            long sequence,
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
                "metadata", Map.of("occurredAt", item.occurredAt().toString(), "sequence", item.sequence()))).toList());
        task.put("metadata", Map.of(
                "messageId", submission.messageId(),
                "delegationId", submission.delegationId(),
                "agentRole", submission.role(),
                "skillId", submission.skill(),
                "sequence", sequence));
        return Map.copyOf(task);
    }

    private static Map<String, Object> error(
            Object id, A2AErrorCodes code, String message, FailureInfo failure) {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("category", failure.category());
        metadata.put("retryable", Boolean.toString(failure.retryable()));
        if (failure.taskState() != null) metadata.put("task_state", "TASK_STATE_" + failure.taskState());
        Map<String, Object> errorInfo = Map.of(
                "@type", "type.googleapis.com/google.rpc.ErrorInfo",
                "reason", failure.reason(),
                "domain", "ai-factory.a2a",
                "metadata", Map.copyOf(metadata));
        Map<String, Object> status = Map.of(
                "@type", "type.googleapis.com/google.rpc.Status",
                "code", failure.grpcCode(), "message", message, "details", List.of(errorInfo));
        return Map.of("jsonrpc", "2.0", "id", id == null ? "null" : id,
                "error", Map.of("code", code.code(), "message", message, "data", status));
    }

    private static FailureInfo classifySubmission(A2aSendMessageService.SubmissionRejected failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (message.contains("unauthenticated")) {
            return new FailureInfo(16, "CALLER_UNAUTHENTICATED", "AUTH", false, null);
        }
        if (message.contains("scope") || message.contains("not admitted")) {
            return new FailureInfo(7, "CALLER_FORBIDDEN", "AUTH", false, "REJECTED");
        }
        if (message.contains("quota") || message.contains("rate limit")) {
            return new FailureInfo(8, "QUOTA_EXCEEDED", "QUOTA", true, "FAILED");
        }
        if (message.contains("collision")) {
            return new FailureInfo(6, "IDEMPOTENCY_CONFLICT", "CONTRACT", false, "REJECTED");
        }
        return new FailureInfo(3, "CONTRACT_INVALID", "CONTRACT", false, "REJECTED");
    }

    private static FailureInfo classify(A2AErrorCodes code, String message) {
        return switch (code) {
            case JSON_PARSE, INVALID_REQUEST -> new FailureInfo(3, "PROTOCOL_INVALID", "CONTRACT", false, null);
            case METHOD_NOT_FOUND, UNSUPPORTED_OPERATION ->
                    new FailureInfo(12, "OPERATION_UNSUPPORTED", "CONTRACT", false, null);
            case VERSION_NOT_SUPPORTED, EXTENSION_SUPPORT_REQUIRED ->
                    new FailureInfo(9, "PROTOCOL_VERSION_UNSUPPORTED", "CONTRACT", false, null);
            default -> new FailureInfo(13, code.name(), "INTERNAL", false, null);
        };
    }

    private static final class RpcFailure extends RuntimeException {
        private final A2AErrorCodes code;
        RpcFailure(A2AErrorCodes code, String message) { super(message); this.code = code; }
    }

    private record FailureInfo(int grpcCode, String reason, String category, boolean retryable, String taskState) {}
}
