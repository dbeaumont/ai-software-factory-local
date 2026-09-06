package com.example.aifactory.service;

import tools.jackson.databind.JsonNode;

import java.util.Set;

/** Execution port used by the control-plane without importing a concrete agent implementation. */
public interface AgentExecutor {
    Result execute(Invocation invocation);

    record Invocation(String taskId, String attemptId, String sourceCommit, String role, String promptName,
                      String outputContract, Set<String> allowedTools, Set<String> allowedReferenceIds,
                      String untrustedInput, AgentToolLoop.Budget budget, String executionMode,
                      String traceId, String runId, String delegationId, String agentRunId) {
        public Invocation(String taskId, String attemptId, String sourceCommit, String role, String promptName,
                          String outputContract, Set<String> allowedTools, Set<String> allowedReferenceIds,
                          String untrustedInput, AgentToolLoop.Budget budget, String executionMode) {
            this(taskId, attemptId, sourceCommit, role, promptName, outputContract, allowedTools,
                    allowedReferenceIds, untrustedInput, budget, executionMode,
                    ExecutionIdentity.deterministic(taskId, attemptId, role, role));
        }

        private Invocation(String taskId, String attemptId, String sourceCommit, String role, String promptName,
                           String outputContract, Set<String> allowedTools, Set<String> allowedReferenceIds,
                           String untrustedInput, AgentToolLoop.Budget budget, String executionMode,
                           ExecutionIdentity identity) {
            this(taskId, attemptId, sourceCommit, role, promptName, outputContract, allowedTools,
                    allowedReferenceIds, untrustedInput, budget, executionMode, identity.traceId(), identity.runId(),
                    identity.delegationId(), identity.agentRunId());
        }
        public Invocation(String taskId, String attemptId, String sourceCommit, String role, String promptName,
                          String outputContract, Set<String> allowedTools, Set<String> allowedReferenceIds,
                          String untrustedInput, AgentToolLoop.Budget budget) {
            this(taskId, attemptId, sourceCommit, role, promptName, outputContract, allowedTools,
                    allowedReferenceIds, untrustedInput, budget, "HIERARCHICAL_ACTIVE");
        }

        public Invocation {
            if (taskId == null || taskId.isBlank() || attemptId == null || attemptId.isBlank()
                    || sourceCommit == null || !sourceCommit.matches("[0-9a-f]{40}")
                    || role == null || role.isBlank() || promptName == null || promptName.isBlank()
                    || outputContract == null || outputContract.isBlank() || untrustedInput == null || budget == null
                    || !OperationalKillSwitch.EXECUTION_MODES.contains(executionMode)
                    || "PIPELINE".equals(executionMode)) {
                throw new IllegalArgumentException("Agent invocation requires explicit identity, prompt, contract and budget");
            }
            allowedTools = Set.copyOf(allowedTools);
            allowedReferenceIds = Set.copyOf(allowedReferenceIds);
            new ExecutionIdentity(traceId, runId, delegationId, agentRunId);
        }

        public ExecutionIdentity executionIdentity() {
            return new ExecutionIdentity(traceId, runId, delegationId, agentRunId);
        }
    }

    record Result(JsonNode document, String promptFingerprint, int turns, int tokens, long costMicros) { }
}
