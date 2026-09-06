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

    @Override
    public synchronized CreateResult createOrGet(StoredTask candidate, HistoryRecord accepted) {
        String existingId = taskByMessage.get(candidate.messageId());
        if (existingId != null) return new CreateResult(byTask.get(existingId), false);
        byTask.put(candidate.taskId(), candidate);
        taskByMessage.put(candidate.messageId(), candidate.taskId());
        histories.put(candidate.taskId(), new ArrayList<>(List.of(accepted)));
        return new CreateResult(candidate, true);
    }

    @Override public Optional<StoredTask> find(String taskId) { return Optional.ofNullable(byTask.get(taskId)); }

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
        return List.of();
    }

    @Override
    public synchronized Optional<StoredTask> transition(
            String taskId, long expectedVersion, A2aSendMessageService.TaskState next, HistoryRecord event) {
        StoredTask current = byTask.get(taskId);
        if (current == null || current.version() != expectedVersion) return Optional.empty();
        StoredTask updated = new StoredTask(current.taskId(), current.contextId(), current.messageId(),
                current.messageDigest(), current.role(), current.skill(), current.callerSubject(), current.tenantId(),
                current.delegationId(), current.submittedAt(), next, expectedVersion + 1);
        byTask.put(taskId, updated);
        histories.get(taskId).add(event);
        return Optional.of(updated);
    }
}
