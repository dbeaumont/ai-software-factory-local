package com.example.aifactory.agentruntime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Local signal projection, replaced by the Temporal workflow gateway in A2A-066. */
final class LocalAgentTaskWorkflowControl implements AgentTaskWorkflowControl {
    private final Set<String> cancellations = ConcurrentHashMap.newKeySet();

    @Override
    public void requestCancellation(String taskId, String contextId, String reason) {
        cancellations.add(taskId);
    }

    boolean cancellationRequested(String taskId) { return cancellations.contains(taskId); }
}
