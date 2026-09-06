package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.A2aDecisionJournal;
import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
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
import java.util.Optional;
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
    private final A2aDecisionJournal audit;
    private final A2aServerMetrics metrics;
    private final A2aTckConformanceService conformance;

    A2aJsonRpcController(ObjectMapper mapper, A2aSendMessageService service, A2aSecurityProperties security,
                         A2aDecisionJournal audit, A2aServerMetrics metrics) {
        this(mapper, service, security, audit, metrics, Optional.empty());
    }

    @Autowired
    A2aJsonRpcController(ObjectMapper mapper, A2aSendMessageService service, A2aSecurityProperties security,
                         A2aDecisionJournal audit, A2aServerMetrics metrics,
                         Optional<A2aTckConformanceService> conformance) {
        this.mapper = mapper;
        this.service = service;
        this.security = security;
        this.audit = audit;
        this.metrics = metrics;
        this.conformance = conformance.orElse(null);
    }

    A2aJsonRpcController(ObjectMapper mapper, A2aSendMessageService service, A2aSecurityProperties security,
                         A2aDecisionJournal audit) {
        this(mapper, service, security, audit, A2aServerMetrics.disabled());
    }

    A2aJsonRpcController(ObjectMapper mapper, A2aSendMessageService service, A2aSecurityProperties security) {
        this(mapper, service, security, new A2aDecisionJournal(), A2aServerMetrics.disabled());
    }

    @PostMapping(path = ENDPOINT, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    reactor.core.publisher.Mono<Map<String, Object>> handleAsync(
            @RequestHeader(name = "A2A-Version", required = false) String version,
            @RequestBody byte[] body,
            Authentication authentication) {
        return reactor.core.publisher.Mono.fromCallable(() -> handle(version, body, authentication))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    @PostMapping(path = ENDPOINT, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    reactor.core.publisher.Flux<ServerSentEvent<Map<String, Object>>> handleStreaming(
            @RequestHeader(name = "A2A-Version", required = false) String version,
            @RequestBody byte[] body,
            Authentication authentication) {
        return reactor.core.publisher.Mono.fromCallable(() -> handle(version, body, authentication))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                .map(payload -> ServerSentEvent.builder(payload).build())
                .flux();
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
            String method = request.path("method").asText();
            if (conformance != null) {
                return response(requestId, conformance.handle(method, request.path("params")));
            }
            return switch (method) {
                case "SendMessage", "message/send" -> response(requestId,
                        task(service.send(request.path("params"), caller(authentication)),
                                A2aSendMessageService.TaskState.SUBMITTED, 0, List.of(), List.of()));
                case "GetTask", "tasks/get" -> {
                    A2aSendMessageService.TaskView view = service.getTask(
                            request.path("params"), caller(authentication));
                    yield response(requestId, task(view.submission(), view.state(), view.sequence(),
                            view.history(), view.artifacts()));
                }
                case "ListTasks", "tasks/list" -> {
                    A2aSendMessageService.TaskPage page = service.listTasks(
                            request.path("params"), caller(authentication));
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("tasks", page.tasks().stream()
                            .map(view -> task(view.submission(), view.state(), view.sequence(),
                                    view.history(), view.artifacts())).toList());
                    result.put("nextPageToken", page.nextPageToken() == null ? "" : page.nextPageToken());
                    yield response(requestId, Map.copyOf(result));
                }
                case "CancelTask", "tasks/cancel" -> {
                    A2aSendMessageService.TaskView view = service.cancelTask(
                            request.path("params"), caller(authentication));
                    yield response(requestId, task(view.submission(), view.state(), view.sequence(),
                            view.history(), view.artifacts()));
                }
                case "SendStreamingMessage", "SubscribeToTask", "GetExtendedAgentCard" ->
                        throw new RpcFailure(A2AErrorCodes.UNSUPPORTED_OPERATION, "A2A operation is not supported");
                case "CreateTaskPushNotificationConfig", "GetTaskPushNotificationConfig",
                     "ListTaskPushNotificationConfigs", "DeleteTaskPushNotificationConfig" ->
                        throw new RpcFailure(A2AErrorCodes.PUSH_NOTIFICATION_NOT_SUPPORTED,
                                "Push notifications are not supported");
                default -> throw new RpcFailure(A2AErrorCodes.METHOD_NOT_FOUND,
                        "A2A method is not available");
            };
        } catch (A2aTckConformanceService.Failure failure) {
            return error(requestId, failure.code, failure.getMessage(), classify(failure.code, failure.getMessage()));
        } catch (RpcFailure failure) {
            recordRefusal(authentication, requestId, A2aDecisionJournal.EventType.REFUSAL);
            return error(requestId, failure.code, failure.getMessage(), classify(failure.code, failure.getMessage()));
        } catch (A2aSendMessageService.SubmissionRejected failure) {
            FailureInfo classification = classifySubmission(failure);
            recordRefusal(authentication, requestId, "AUTH".equals(classification.category())
                    ? A2aDecisionJournal.EventType.AUTHENTICATION : A2aDecisionJournal.EventType.REFUSAL);
            return error(requestId, A2AErrorCodes.INVALID_PARAMS, failure.getMessage(), classifySubmission(failure));
        } catch (A2aSendMessageService.TaskLookupRejected failure) {
            recordRefusal(authentication, requestId, A2aDecisionJournal.EventType.REFUSAL);
            return error(requestId, A2AErrorCodes.TASK_NOT_FOUND, failure.getMessage(),
                    new FailureInfo(5, "TASK_NOT_FOUND", "LOOKUP", false, null));
        } catch (A2aSendMessageService.TaskNotCancelable failure) {
            recordRefusal(authentication, requestId, A2aDecisionJournal.EventType.REFUSAL);
            return error(requestId, A2AErrorCodes.TASK_NOT_CANCELABLE, failure.getMessage(),
                    new FailureInfo(9, "TASK_NOT_CANCELABLE", "BUSINESS", false, "REJECTED"));
        } catch (A2aOperationalException failure) {
            recordRefusal(authentication, requestId, A2aDecisionJournal.EventType.REFUSAL);
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
            recordRefusal(authentication, requestId, A2aDecisionJournal.EventType.AUTHENTICATION);
            return error(requestId, A2AErrorCodes.INVALID_PARAMS, "A2A operation is not authorized",
                    new FailureInfo(7, "POLICY_DENIED", "AUTH", false, "REJECTED"));
        } catch (IllegalArgumentException failure) {
            recordRefusal(authentication, requestId, A2aDecisionJournal.EventType.REFUSAL);
            return error(requestId, A2AErrorCodes.INVALID_PARAMS, "A2A contract validation failed",
                    new FailureInfo(3, "CONTRACT_INVALID", "CONTRACT", false, "REJECTED"));
        } catch (Exception failure) {
            return error(requestId, A2AErrorCodes.INTERNAL, "Internal A2A processing failure",
                    new FailureInfo(13, "INTERNAL_FAILURE", "INTERNAL", true, "FAILED"));
        }
    }

    private void recordRefusal(Authentication authentication, Object requestId, A2aDecisionJournal.EventType type) {
        if (type == A2aDecisionJournal.EventType.AUTHENTICATION) metrics.authenticationRefusal();
        audit.record(type, A2aDecisionJournal.Outcome.DENIED,
                authentication == null ? null : authentication.getName(), null,
                requestId == null ? null : requestId.toString());
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
            String role = jwt.getToken().getClaimAsString("role");
            String expectedClient = "workflow".equals(role)
                    ? "ai-factory-orchestrator" : "ai-factory-agent-" + role;
            if (clientId == null || !clientId.equals(expectedClient)
                    || tenantId == null || tenantId.isBlank()) {
                throw new A2aSendMessageService.SubmissionRejected(
                        "Authenticated A2A caller lacks client or tenant binding");
            }
            return new A2aSendMessageService.Caller(clientId, tenantId, role, scopes);
        }
        return new A2aSendMessageService.Caller(
                authentication.getName(), authentication.getName(), "workflow", scopes);
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
                "domain", "a2a-protocol.org",
                "metadata", Map.copyOf(metadata));
        return Map.of("jsonrpc", "2.0", "id", id == null ? "null" : id,
                "error", Map.of("code", code.code(), "message", message, "data", List.of(errorInfo)));
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
                    new FailureInfo(12, code == A2AErrorCodes.UNSUPPORTED_OPERATION
                            ? "UNSUPPORTED_OPERATION" : "METHOD_NOT_FOUND", "CONTRACT", false, null);
            case VERSION_NOT_SUPPORTED, EXTENSION_SUPPORT_REQUIRED ->
                    new FailureInfo(9, code == A2AErrorCodes.VERSION_NOT_SUPPORTED
                            ? "VERSION_NOT_SUPPORTED" : "EXTENSION_SUPPORT_REQUIRED", "CONTRACT", false, null);
            case TASK_NOT_FOUND -> new FailureInfo(5, "TASK_NOT_FOUND", "LOOKUP", false, null);
            case TASK_NOT_CANCELABLE -> new FailureInfo(9, "TASK_NOT_CANCELABLE", "BUSINESS", false, null);
            case PUSH_NOTIFICATION_NOT_SUPPORTED ->
                    new FailureInfo(12, "PUSH_NOTIFICATION_NOT_SUPPORTED", "CONTRACT", false, null);
            case CONTENT_TYPE_NOT_SUPPORTED ->
                    new FailureInfo(3, "CONTENT_TYPE_NOT_SUPPORTED", "CONTRACT", false, null);
            default -> new FailureInfo(13, code.name(), "INTERNAL", false, null);
        };
    }

    private static final class RpcFailure extends RuntimeException {
        private final A2AErrorCodes code;
        RpcFailure(A2AErrorCodes code, String message) { super(message); this.code = code; }
    }

    private record FailureInfo(int grpcCode, String reason, String category, boolean retryable, String taskState) {}
}
