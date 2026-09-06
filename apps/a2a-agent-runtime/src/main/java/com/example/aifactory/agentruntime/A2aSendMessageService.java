package com.example.aifactory.agentruntime;

import org.erdtman.jcs.JsonCanonicalizer;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AgentTaskWorkflowControl workflowControl;
    private final AgentTaskWorkflowStarter workflowStarter;
    private final A2aTaskStore store;
    private final A2aAdmissionController admission;
    private final Map<String, Cursor> cursors = new ConcurrentHashMap<>();

    @Autowired
    public A2aSendMessageService(AgentRuntimeProperties runtime, AgentCardCatalogGenerator cards,
                                 ObjectMapper mapper, AgentTaskWorkflowControl workflowControl,
                                 AgentTaskWorkflowStarter workflowStarter, A2aTaskStore store,
                                 A2aAdmissionController admission) {
        this.activeRole = runtime.role();
        AgentCardCatalogGenerator.GeneratedAgentCard card = cards.generate().get(activeRole);
        if (card == null) throw new IllegalStateException("No Agent Card source for active role");
        this.allowedSkills = card.skills().stream()
                .map(AgentCardCatalogGenerator.GeneratedSkill::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.mapper = mapper;
        this.workflowControl = workflowControl;
        this.workflowStarter = workflowStarter;
        this.store = store;
        this.admission = admission;
    }

    A2aSendMessageService(AgentRuntimeProperties runtime, AgentCardCatalogGenerator cards,
                          ObjectMapper mapper, AgentTaskWorkflowControl workflowControl,
                          AgentTaskWorkflowStarter workflowStarter, A2aTaskStore store) {
        this(runtime, cards, mapper, workflowControl, workflowStarter, store,
                new A2aAdmissionController(store, new AgentConcurrencyProperties(
                        2, 2, 32, 16, 64, 1_000, java.time.Duration.ofSeconds(30))));
    }

    A2aSendMessageService(AgentRuntimeProperties runtime, AgentCardCatalogGenerator cards, ObjectMapper mapper) {
        this(runtime, cards, mapper, (taskId, contextId, reason) -> { },
                (submission, envelope) -> new AgentTaskWorkflowStarter.Execution("test", "test"),
                new InMemoryA2aTaskStore());
    }

    A2aSendMessageService(AgentRuntimeProperties runtime, AgentCardCatalogGenerator cards, ObjectMapper mapper,
                          AgentTaskWorkflowControl workflowControl) {
        this(runtime, cards, mapper, workflowControl,
                (submission, envelope) -> new AgentTaskWorkflowStarter.Execution("test", "test"),
                new InMemoryA2aTaskStore());
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
        Instant now = Instant.now();
        String requestedTaskId = optionalText(message, "taskId");
        String requestedContextId = optionalText(message, "contextId");
        if (requestedTaskId != null || requestedContextId != null) {
            if (requestedTaskId == null || requestedContextId == null) {
                throw new SubmissionRejected("A2A continuation requires both taskId and contextId");
            }
            return continueTask(requestedTaskId, requestedContextId, messageId, digest, envelope,
                    role, skill, caller, execution, now);
        }
        A2aTaskStore.StoredTask candidate = new A2aTaskStore.StoredTask(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), messageId, digest,
                role, skill, caller.subject(), caller.tenantId(), requiredText(execution, "delegationId"),
                now, TaskState.SUBMITTED, 0, envelope.toString(), null, null);
        A2aTaskStore.CreateResult result = admission.admit(messageId, role, caller.tenantId(), () ->
                store.createOrGet(candidate,
                        new A2aTaskStore.HistoryRecord(messageId, "MESSAGE_ACCEPTED", now)));
        if (!result.task().messageDigest().equals(digest)) {
            throw new SubmissionRejected("messageId collision with a different payload");
        }
        Submission submission = submission(result.task());
        if (result.created()) {
            AgentTaskWorkflowStarter.Execution executionReference = workflowStarter.start(submission, envelope.toString());
            store.recordWorkflowExecution(submission.taskId(), executionReference.workflowId(), executionReference.runId());
        }
        return submission;
    }

    private Submission continueTask(String taskId, String contextId, String messageId, String digest,
                                    JsonNode envelope, String role, String skill, Caller caller,
                                    JsonNode execution, Instant now) {
        A2aTaskStore.StoredTask current = store.find(taskId)
                .orElseThrow(() -> new TaskLookupRejected("Task not found"));
        if (!current.contextId().equals(contextId) || !current.callerSubject().equals(caller.subject())
                || !current.tenantId().equals(caller.tenantId()) || !current.role().equals(role)
                || !current.skill().equals(skill)
                || !current.delegationId().equals(requiredText(execution, "delegationId"))) {
            throw new TaskLookupRejected("Task not found");
        }
        A2aTaskStore.ContinueResult result;
        try {
            result = store.continueTask(taskId, contextId, messageId, digest, envelope.toString(),
                    new A2aTaskStore.HistoryRecord(messageId, "MESSAGE_CONTINUED", now));
        } catch (IllegalStateException rejected) {
            throw new SubmissionRejected(rejected.getMessage(), rejected);
        }
        if (result.accepted()) {
            workflowControl.requestContinuation(taskId, contextId, messageId, envelope.toString());
        }
        return submission(result.task());
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
        A2aTaskStore.StoredTask task = store.find(taskId).orElseThrow(() -> new TaskLookupRejected("Task not found"));
        Submission submission = submission(task);
        try {
            requireScopes(caller.scopes(), Set.of("a2a.read", "a2a.role." + submission.role()));
        } catch (SubmissionRejected forbidden) {
            throw new TaskLookupRejected("Task not found");
        }
        if (!submission.caller().equals(caller.subject()) || !submission.tenantId().equals(caller.tenantId())) {
            throw new TaskLookupRejected("Task not found");
        }
        List<HistoryItem> history = historyLength == 0 ? List.of()
                : store.history(taskId, historyLength).stream().map(A2aSendMessageService::history).toList();
        return new TaskView(submission, task.state(), task.version(), history,
                store.artifacts(taskId, caller.tenantId(), caller.subject()));
    }

    public TaskView cancelTask(JsonNode params, Caller caller) {
        String taskId = requiredText(params, "id");
        A2aTaskStore.StoredTask task = store.find(taskId).orElseThrow(() -> new TaskLookupRejected("Task not found"));
        if (caller == null) throw new TaskLookupRejected("Task not found");
        Submission submission = submission(task);
        try {
            requireScopes(caller.scopes(), Set.of("a2a.cancel", "a2a.role." + submission.role()));
        } catch (SubmissionRejected forbidden) {
            throw new TaskLookupRejected("Task not found");
        }
        if (!submission.caller().equals(caller.subject()) || !submission.tenantId().equals(caller.tenantId())) {
            throw new TaskLookupRejected("Task not found");
        }
        while (true) {
            if (task.state() == TaskState.CANCELED) {
                return view(task, MAX_HISTORY_LENGTH, caller);
            }
            if (task.state().terminal()) {
                throw new TaskNotCancelable("Task is already terminal: " + task.state());
            }
            java.util.Optional<A2aTaskStore.StoredTask> updated = store.transition(taskId, task.version(),
                    TaskState.CANCELED, new A2aTaskStore.HistoryRecord(
                            submission.messageId(), "TASK_CANCELED", Instant.now()));
            if (updated.isPresent()) {
                workflowControl.requestCancellation(taskId, submission.contextId(), "A2A tasks/cancel");
                return view(updated.get(), MAX_HISTORY_LENGTH, caller);
            }
            task = store.find(taskId).orElseThrow(() -> new TaskLookupRejected("Task not found"));
        }
    }

    void projectState(String taskId, TaskState state) {
        while (true) {
            A2aTaskStore.StoredTask task = store.find(taskId)
                    .orElseThrow(() -> new TaskLookupRejected("Task not found"));
            if (store.transition(taskId, task.version(), state, new A2aTaskStore.HistoryRecord(
                    task.messageId(), "TASK_" + state.name(), Instant.now())).isPresent()) return;
        }
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
        TaskState stateFilter = state == null ? null : TaskState.fromProtocol(state);
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
        int total = store.count(caller.tenantId(), caller.subject(), contextId, stateFilter);
        List<TaskView> tasks = store.list(caller.tenantId(), caller.subject(), contextId, stateFilter, offset, pageSize)
                .stream().map(task -> new TaskView(submission(task), task.state(), task.version(), List.of(),
                        store.artifacts(task.taskId(), caller.tenantId(), caller.subject()))).toList();
        int end = offset + tasks.size();
        String next = null;
        if (end < total) {
            next = UUID.randomUUID().toString();
            cursors.put(next, new Cursor(caller.subject(), caller.tenantId(), contextId, state, end));
        }
        return new TaskPage(tasks, next);
    }

    private TaskView view(A2aTaskStore.StoredTask task, int historyLength, Caller caller) {
        return new TaskView(submission(task), task.state(), task.version(),
                store.history(task.taskId(), historyLength).stream().map(A2aSendMessageService::history).toList(),
                store.artifacts(task.taskId(), caller.tenantId(), caller.subject()));
    }

    private static Submission submission(A2aTaskStore.StoredTask task) {
        return new Submission(task.taskId(), task.contextId(), task.messageId(), task.role(), task.skill(),
                task.callerSubject(), task.tenantId(), task.delegationId(), task.submittedAt());
    }

    private static HistoryItem history(A2aTaskStore.HistoryRecord history) {
        return new HistoryItem(history.messageId(), history.event(), history.occurredAt(), history.taskVersion());
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

    public record HistoryItem(String messageId, String event, Instant occurredAt, long sequence) {}

    public record TaskView(
            Submission submission, TaskState state, long sequence,
            List<HistoryItem> history, List<Map<String, Object>> artifacts) {
        public TaskView {
            history = List.copyOf(history);
            artifacts = List.copyOf(artifacts);
        }
    }

    public enum TaskState {
        SUBMITTED(false), WORKING(false), INPUT_REQUIRED(false), AUTH_REQUIRED(false),
        COMPLETED(true), REJECTED(true), FAILED(true), CANCELED(true);
        private final boolean terminal;
        TaskState(boolean terminal) { this.terminal = terminal; }
        public boolean terminal() { return terminal; }
        static TaskState fromProtocol(String value) {
            try {
                return valueOf(value.replace("TASK_STATE_", ""));
            } catch (RuntimeException exception) {
                throw new SubmissionRejected("Unsupported task status filter");
            }
        }
    }

    public record TaskPage(List<TaskView> tasks, String nextPageToken) {
        public TaskPage { tasks = List.copyOf(tasks); }
    }

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

    public static final class TaskNotCancelable extends RuntimeException {
        public TaskNotCancelable(String message) { super(message); }
    }
}
