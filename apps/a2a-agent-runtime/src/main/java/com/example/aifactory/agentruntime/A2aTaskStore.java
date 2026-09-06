package com.example.aifactory.agentruntime;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Durable authority for the A2A task projection, ACL and idempotency identity. */
public interface A2aTaskStore {
    CreateResult createOrGet(StoredTask candidate, HistoryRecord accepted);
    Optional<StoredTask> find(String taskId);
    List<StoredTask> list(String tenantId, String callerSubject, String contextId,
                          A2aSendMessageService.TaskState state, int offset, int limit);
    int count(String tenantId, String callerSubject, String contextId, A2aSendMessageService.TaskState state);
    List<HistoryRecord> history(String taskId, int limit);
    List<Map<String, Object>> artifacts(String taskId, String tenantId, String callerSubject);
    Optional<StoredTask> transition(String taskId, long expectedVersion,
                                    A2aSendMessageService.TaskState next, HistoryRecord event);

    record StoredTask(
            String taskId, String contextId, String messageId, String messageDigest,
            String role, String skill, String callerSubject, String tenantId,
            String delegationId, Instant submittedAt, A2aSendMessageService.TaskState state, long version) {}

    record HistoryRecord(String messageId, String event, Instant occurredAt) {}

    record CreateResult(StoredTask task, boolean created) {}
}
