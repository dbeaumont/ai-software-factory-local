package com.example.aifactory.a2a;

import io.temporal.api.enums.v1.WorkflowExecutionStatus;

import java.time.Duration;

/** Reads the authoritative Temporal status of an agent-owned A2A task workflow. */
public interface A2aAgentWorkflowProbe {
    WorkflowExecutionStatus status(String agentRole, String a2aTaskId, Duration timeout);
}
