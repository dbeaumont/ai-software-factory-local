package com.example.aifactory.agentruntime;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Test/local fallback preserving the same uniqueness and compare-and-set semantics as PostgreSQL. */
public final class InMemoryA2aTaskStore implements A2aTaskStore {
    private final Map<String, StoredTask> byTask = new ConcurrentHashMap<>();
    private final Map<String, String> taskByMessage = new ConcurrentHashMap<>();
    private final Map<String, List<HistoryRecord>> histories = new ConcurrentHashMap<>();
    private final Map<String, PendingNotification> notifications = new ConcurrentHashMap<>();
    private final Map<String, ArtifactRecord> artifactRecords = new ConcurrentHashMap<>();

    @Override
    public synchronized CreateResult createOrGet(StoredTask candidate, HistoryRecord accepted) {
        String existingId = taskByMessage.get(candidate.messageId());
        if (existingId != null) return new CreateResult(byTask.get(existingId), false);
        byTask.put(candidate.taskId(), candidate);
        taskByMessage.put(candidate.messageId(), candidate.taskId());
        histories.put(candidate.taskId(), new ArrayList<>(List.of(new HistoryRecord(
                accepted.messageId(), accepted.event(), accepted.occurredAt(), candidate.version()))));
        return new CreateResult(candidate, true);
    }

    @Override public Optional<StoredTask> find(String taskId) { return Optional.ofNullable(byTask.get(taskId)); }

    @Override
    public Optional<StoredTask> findByMessageId(String messageId) {
        return Optional.ofNullable(taskByMessage.get(messageId)).map(byTask::get);
    }

    @Override
    public List<StoredTask> list(String tenantId, String callerSubject, String contextId,
                                 A2aSendMessageService.TaskState state, int offset, int limit) {
        return byTask.values().stream()
                .filter(task -> task.tenantId().equals(tenantId) && task.callerSubject().equals(callerSubject))
                .filter(task -> contextId == null || task.contextId().equals(contextId))
                .filter(task -> state == null || task.state() == state)
                .sorted(Comparator.comparing(StoredTask::submittedAt).thenComparing(StoredTask::taskId))
                .skip(offset).limit(limit).toList();
    }

    @Override
    public int count(String tenantId, String callerSubject, String contextId, A2aSendMessageService.TaskState state) {
        return list(tenantId, callerSubject, contextId, state, 0, Integer.MAX_VALUE).size();
    }

    @Override
    public synchronized List<HistoryRecord> history(String taskId, int limit) {
        List<HistoryRecord> values = histories.getOrDefault(taskId, List.of());
        return List.copyOf(values.subList(Math.max(0, values.size() - limit), values.size()));
    }

    @Override
    public List<Map<String, Object>> artifacts(String taskId, String tenantId, String callerSubject) {
        return artifactRecords.values().stream()
                .filter(artifact -> artifact.taskId().equals(taskId) && artifact.tenantId().equals(tenantId)
                        && artifact.aclSubject().equals(callerSubject))
                .sorted(Comparator.comparing(ArtifactRecord::artifactId)).map(ArtifactRecord::document).toList();
    }

    @Override
    public synchronized Optional<StoredTask> transition(
            String taskId, long expectedVersion, A2aSendMessageService.TaskState next, HistoryRecord event) {
        StoredTask current = byTask.get(taskId);
        if (current == null || current.version() != expectedVersion) return Optional.empty();
        StoredTask updated = new StoredTask(current.taskId(), current.contextId(), current.messageId(),
                current.messageDigest(), current.role(), current.skill(), current.callerSubject(), current.tenantId(),
                current.delegationId(), current.submittedAt(), next, expectedVersion + 1,
                current.envelopeJson(), current.workflowId(), current.workflowRunId());
        byTask.put(taskId, updated);
        histories.get(taskId).add(new HistoryRecord(
                event.messageId(), event.event(), event.occurredAt(), expectedVersion + 1));
        return Optional.of(updated);
    }

    @Override
    public synchronized void recordWorkflowExecution(String taskId, String workflowId, String runId) {
        StoredTask current = byTask.get(taskId);
        if (current == null) throw new IllegalStateException("A2A task is absent");
        if (current.workflowId() != null && !current.workflowId().equals(workflowId)) {
            throw new IllegalStateException("A2A task is already bound to another workflow");
        }
        byTask.put(taskId, new StoredTask(current.taskId(), current.contextId(), current.messageId(),
                current.messageDigest(), current.role(), current.skill(), current.callerSubject(), current.tenantId(),
                current.delegationId(), current.submittedAt(), current.state(), current.version(),
                current.envelopeJson(), workflowId, runId));
    }

    @Override
    public List<StoredTask> nonTerminal(String role, int limit) {
        return byTask.values().stream().filter(task -> task.role().equals(role) && !task.state().terminal())
                .sorted(Comparator.comparing(StoredTask::submittedAt).thenComparing(StoredTask::taskId))
                .limit(limit).toList();
    }

    @Override
    public void enqueueNotification(PendingNotification notification) {
        notifications.putIfAbsent(notification.notificationId(), notification);
    }

    @Override
    public List<PendingNotification> pendingNotifications(String role, int limit) {
        return notifications.values().stream().filter(notification -> notification.role().equals(role))
                .sorted(Comparator.comparing(PendingNotification::occurredAt)
                        .thenComparing(PendingNotification::notificationId))
                .limit(limit).toList();
    }

    @Override
    public void acknowledgeNotification(String notificationId, java.time.Instant acknowledgedAt) {
        notifications.remove(notificationId);
    }

    @Override
    public void putArtifact(ArtifactRecord artifact) {
        ArtifactRecord existing = artifactRecords.putIfAbsent(artifact.artifactId(), artifact);
        if (existing != null && (!existing.digest().equals(artifact.digest())
                || !existing.document().equals(artifact.document()))) {
            throw new IllegalStateException("Immutable A2A artifact conflict");
        }
    }

    @Override public void checkHealth() { }

    @Override
    public int activeCount(String role, String tenantId) {
        return (int) byTask.values().stream().filter(task -> task.role().equals(role) && !task.state().terminal())
                .filter(task -> tenantId == null || task.tenantId().equals(tenantId)).count();
    }
}
