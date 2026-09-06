package com.example.aifactory.agentruntime;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Durable authority for the A2A task projection, ACL and idempotency identity. */
public interface A2aTaskStore {
    CreateResult createOrGet(StoredTask candidate, HistoryRecord accepted);
    Optional<StoredTask> find(String taskId);
    Optional<StoredTask> findByMessageId(String messageId);
    List<StoredTask> list(String tenantId, String callerSubject, String contextId,
                          A2aSendMessageService.TaskState state, int offset, int limit);
    int count(String tenantId, String callerSubject, String contextId, A2aSendMessageService.TaskState state);
    List<HistoryRecord> history(String taskId, int limit);
    List<Map<String, Object>> artifacts(String taskId, String tenantId, String callerSubject);
    Optional<StoredTask> transition(String taskId, long expectedVersion,
                                    A2aSendMessageService.TaskState next, HistoryRecord event);
    void recordWorkflowExecution(String taskId, String workflowId, String runId);
    List<StoredTask> nonTerminal(String role, int limit);
    void enqueueNotification(PendingNotification notification);
    List<PendingNotification> pendingNotifications(String role, int limit);
    void acknowledgeNotification(String notificationId, Instant acknowledgedAt);
    void putArtifact(ArtifactRecord artifact);
    void checkHealth();
    int activeCount(String role, String tenantId);

    record StoredTask(
            String taskId, String contextId, String messageId, String messageDigest,
            String role, String skill, String callerSubject, String tenantId,
            String delegationId, Instant submittedAt, A2aSendMessageService.TaskState state, long version,
            String envelopeJson, String workflowId, String workflowRunId) {}

    record HistoryRecord(String messageId, String event, Instant occurredAt, long taskVersion) {
        public HistoryRecord(String messageId, String event, Instant occurredAt) {
            this(messageId, event, occurredAt, -1);
        }
    }

    record CreateResult(StoredTask task, boolean created) {}

    record PendingNotification(
            String notificationId, String taskId, String contextId, String role, long sequence,
            A2aSendMessageService.TaskState state, Instant occurredAt) {}

    record ArtifactRecord(
            String artifactId, String taskId, String tenantId, String aclSubject,
            String digest, Map<String, Object> document) {}
}
