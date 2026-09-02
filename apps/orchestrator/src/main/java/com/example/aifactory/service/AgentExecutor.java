package com.example.aifactory.service;

/** Execution port used by role-specific, host-enforced agent policies. */
public interface AgentExecutor {
    AgentRuntime.Result execute(AgentRuntime.Invocation invocation);
}
