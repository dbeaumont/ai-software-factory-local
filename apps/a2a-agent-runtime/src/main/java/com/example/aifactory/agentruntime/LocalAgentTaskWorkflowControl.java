package com.example.aifactory.agentruntime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Local signal projection, replaced by the Temporal workflow gateway in A2A-066. */
final class LocalAgentTaskWorkflowControl implements AgentTaskWorkflowControl {
    private final Set<String> cancellations = ConcurrentHashMap.newKeySet();
    private final Set<String> continuations = ConcurrentHashMap.newKeySet();

    @Override
    public void requestCancellation(String taskId, String contextId, String reason) {
        cancellations.add(taskId);
    }

    @Override
    public void requestContinuation(String taskId, String contextId, String messageId, String envelopeJson) {
        continuations.add(messageId);
    }

    boolean cancellationRequested(String taskId) { return cancellations.contains(taskId); }

    boolean continuationRequested(String messageId) { return continuations.contains(messageId); }
}
