package com.example.aifactory.agentruntime;

import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Validates and deduplicates asynchronous message/send submissions before durable execution starts. */
@Service
public final class A2aSendMessageService {
    static final String EXECUTION_CONTEXT_EXTENSION =
            "https://ai-factory.local/extensions/execution-context/v1";
    private static final int MAX_PARTS = 16;
    static final int MAX_HISTORY_LENGTH = 50;
    static final int MAX_PAGE_SIZE = 100;

    private final String activeRole;
    private final Set<String> allowedSkills;
    private final ObjectMapper mapper;
    private final Map<String, RegisteredSubmission> byMessageId = new ConcurrentHashMap<>();
    private final Map<String, RegisteredSubmission> byTaskId = new ConcurrentHashMap<>();
    private final Map<String, Cursor> cursors = new ConcurrentHashMap<>();

    public A2aSendMessageService(AgentRuntimeProperties runtime, AgentCardCatalogGenerator cards,
                                 ObjectMapper mapper) {
        this.activeRole = runtime.role();
        AgentCardCatalogGenerator.GeneratedAgentCard card = cards.generate().get(activeRole);
        if (card == null) throw new IllegalStateException("No Agent Card source for active role");
        this.allowedSkills = card.skills().stream()
                .map(AgentCardCatalogGenerator.GeneratedSkill::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.mapper = mapper;
    }

    public Submission send(JsonNode params, Caller caller) {
        if (caller == null || caller.subject() == null || caller.subject().isBlank()) {
            throw new SubmissionRejected("Unauthenticated A2A caller");
        }
        JsonNode message = requiredObject(params, "message");
        String messageId = requiredText(message, "messageId");
        JsonNode parts = message.path("parts");
        if (!parts.isArray() || parts.isEmpty() || parts.size() > MAX_PARTS) {
            throw new SubmissionRejected("A2A message parts must contain between 1 and " + MAX_PARTS + " items");
        }
        JsonNode envelope = requiredObject(parts.get(0), "data");
        String role = requiredText(envelope, "target_role");
        String skill = requiredText(envelope, "skill_id");
        if (!activeRole.equals(role) || !allowedSkills.contains(skill)) {
            throw new SubmissionRejected("Role or skill is not admitted by this runtime");
        }
        requireScopes(caller.scopes(), Set.of("a2a.invoke", "a2a.role." + role, "a2a.skill." + skill));
        JsonNode metadata = requiredObject(message, "metadata");
        JsonNode execution = requiredObject(metadata, EXECUTION_CONTEXT_EXTENSION);
        validateExecutionContext(execution, role);
        if (params.path("configuration").path("blocking").asBoolean(false)) {
            throw new SubmissionRejected("Blocking message/send is disabled");
        }

        String digest = digest(message);
        synchronized (byMessageId) {
            RegisteredSubmission existing = byMessageId.get(messageId);
            if (existing != null) {
                if (!existing.messageDigest().equals(digest)) {
                    throw new SubmissionRejected("messageId collision with a different payload");
                }
                return existing.submission();
            }
            Submission created = new Submission(
                    UUID.randomUUID().toString(), UUID.randomUUID().toString(), messageId,
                    role, skill, caller.subject(), caller.tenantId(),
                    requiredText(execution, "delegationId"), Instant.now());
            RegisteredSubmission registered = new RegisteredSubmission(digest, created,
                    List.of(new HistoryItem(messageId, "MESSAGE_ACCEPTED", created.submittedAt())));
            byMessageId.put(messageId, registered);
            byTaskId.put(created.taskId(), registered);
            return created;
        }
    }

    public TaskView getTask(JsonNode params, Caller caller) {
        if (caller == null || caller.subject() == null || caller.subject().isBlank()) {
            throw new TaskLookupRejected("Task not found");
        }
        String taskId = requiredText(params, "id");
        int historyLength = params.path("historyLength").asInt(0);
        if (historyLength < 0 || historyLength > MAX_HISTORY_LENGTH) {
            throw new SubmissionRejected("historyLength must be between 0 and " + MAX_HISTORY_LENGTH);
        }
        RegisteredSubmission task = byTaskId.get(taskId);
        if (task == null) throw new TaskLookupRejected("Task not found");
        Submission submission = task.submission();
        try {
            requireScopes(caller.scopes(), Set.of("a2a.read", "a2a.role." + submission.role()));
        } catch (SubmissionRejected forbidden) {
            throw new TaskLookupRejected("Task not found");
        }
        if (!submission.caller().equals(caller.subject()) || !submission.tenantId().equals(caller.tenantId())) {
            throw new TaskLookupRejected("Task not found");
        }
        int from = Math.max(0, task.history().size() - historyLength);
        List<HistoryItem> history = historyLength == 0 ? List.of() : task.history().subList(from, task.history().size());
        return new TaskView(submission, history, List.of());
    }

    public TaskPage listTasks(JsonNode params, Caller caller) {
        if (caller == null || caller.subject() == null || caller.subject().isBlank()) {
            throw new TaskLookupRejected("Tasks not found");
        }
        requireScopes(caller.scopes(), Set.of("a2a.read", "a2a.role." + activeRole));
        int pageSize = params.path("pageSize").asInt(50);
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new SubmissionRejected("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        String contextId = optionalText(params, "contextId");
        String state = optionalText(params, "status");
        if (state != null && !"TASK_STATE_SUBMITTED".equals(state)) {
            throw new SubmissionRejected("Unsupported task status filter");
        }
        String pageToken = optionalText(params, "pageToken");
        int offset = 0;
        if (pageToken != null) {
            Cursor cursor = cursors.get(pageToken);
            if (cursor == null || !cursor.matches(caller, contextId, state)) {
                throw new SubmissionRejected("Invalid or foreign pageToken");
            }
            cursors.remove(pageToken, cursor);
            offset = cursor.offset();
        }
        List<RegisteredSubmission> visible = byTaskId.values().stream()
                .filter(task -> task.submission().tenantId().equals(caller.tenantId()))
                .filter(task -> task.submission().caller().equals(caller.subject()))
                .filter(task -> contextId == null || task.submission().contextId().equals(contextId))
                .sorted(java.util.Comparator.comparing(task -> task.submission().submittedAt()))
                .toList();
        int end = Math.min(visible.size(), offset + pageSize);
        List<TaskView> tasks = visible.subList(Math.min(offset, visible.size()), end).stream()
                .map(task -> new TaskView(task.submission(), List.of(), List.of())).toList();
        String next = null;
        if (end < visible.size()) {
            next = UUID.randomUUID().toString();
            cursors.put(next, new Cursor(caller.subject(), caller.tenantId(), contextId, state, end));
        }
        return new TaskPage(tasks, next);
    }

    private void validateExecutionContext(JsonNode execution, String role) {
        if (!"1".equals(requiredText(execution, "schemaVersion"))
                || !role.equals(requiredText(execution, "agentRole"))) {
            throw new SubmissionRejected("Execution context does not match the target role");
        }
        for (String field : List.of("taskId", "attemptId", "workflowId", "workflowRunId",
                "repositoryId", "delegationId")) {
            String value = requiredText(execution, field);
            if (value.length() > 200 || !value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
                throw new SubmissionRejected("Invalid execution context field " + field);
            }
        }
        if (!requiredText(execution, "sourceCommit").matches("[a-f0-9]{40}")) {
            throw new SubmissionRejected("Invalid source commit");
        }
        JsonNode digests = execution.path("inputDigests");
        if (!digests.isArray() || digests.isEmpty() || digests.size() > 32) {
            throw new SubmissionRejected("Invalid input digests");
        }
        digests.forEach(value -> {
            if (!value.asText().matches("[a-f0-9]{64}")) throw new SubmissionRejected("Invalid input digest");
        });
    }

    private static void requireScopes(Set<String> actual, Set<String> required) {
        if (actual == null || !actual.containsAll(required)) {
            throw new SubmissionRejected("Caller lacks required role or skill scope");
        }
    }

    private String digest(JsonNode message) {
        try {
            byte[] canonical = new JsonCanonicalizer(mapper.writeValueAsBytes(message)).getEncodedUTF8();
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception exception) {
            throw new SubmissionRejected("Cannot digest A2A message", exception);
        }
    }

    private static JsonNode requiredObject(JsonNode source, String field) {
        JsonNode value = source == null ? null : source.get(field);
        if (value == null || !value.isObject()) throw new SubmissionRejected("Missing object " + field);
        return value;
    }

    private static String requiredText(JsonNode source, String field) {
        JsonNode value = source == null ? null : source.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new SubmissionRejected("Missing text " + field);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode source, String field) {
        JsonNode value = source == null ? null : source.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new SubmissionRejected(field + " must be non-blank text");
        }
        return value.asText();
    }

    public record Caller(String subject, String tenantId, Set<String> scopes) {
        public Caller {
            if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId is required");
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }
        public Caller(String subject, Set<String> scopes) { this(subject, subject, scopes); }
    }

    public record Submission(
            String taskId,
            String contextId,
            String messageId,
            String role,
            String skill,
            String caller,
            String tenantId,
            String delegationId,
            Instant submittedAt) {}

    public record HistoryItem(String messageId, String event, Instant occurredAt) {}

    public record TaskView(Submission submission, List<HistoryItem> history, List<Map<String, Object>> artifacts) {
        public TaskView {
            history = List.copyOf(history);
            artifacts = List.copyOf(artifacts);
        }
    }

    public record TaskPage(List<TaskView> tasks, String nextPageToken) {
        public TaskPage { tasks = List.copyOf(tasks); }
    }

    private record RegisteredSubmission(
            String messageDigest, Submission submission, List<HistoryItem> history) {}

    private record Cursor(String subject, String tenantId, String contextId, String state, int offset) {
        boolean matches(Caller caller, String requestedContext, String requestedState) {
            return subject.equals(caller.subject()) && tenantId.equals(caller.tenantId())
                    && java.util.Objects.equals(contextId, requestedContext)
                    && java.util.Objects.equals(state, requestedState);
        }
    }

    public static final class SubmissionRejected extends RuntimeException {
        public SubmissionRejected(String message) { super(message); }
        public SubmissionRejected(String message, Throwable cause) { super(message, cause); }
    }

    public static final class TaskLookupRejected extends RuntimeException {
        public TaskLookupRejected(String message) { super(message); }
    }
}
