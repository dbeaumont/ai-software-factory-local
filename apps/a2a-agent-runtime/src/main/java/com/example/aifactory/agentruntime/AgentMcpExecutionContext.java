package com.example.aifactory.agentruntime;

import java.time.Instant;
import java.util.concurrent.Callable;

/** Immutable host-owned metadata bound to every MCP call made during one agent execution. */
record AgentMcpExecutionContext(String taskId, String attemptId, String sourceCommit, Instant deadline) {
    private static final ThreadLocal<AgentMcpExecutionContext> CURRENT = new ThreadLocal<>();

    AgentMcpExecutionContext {
        if (taskId == null || taskId.isBlank() || attemptId == null || attemptId.isBlank()
                || sourceCommit == null || !sourceCommit.matches("[0-9a-f]{40}")
                || deadline == null) {
            throw new IllegalArgumentException("Agent MCP execution context is invalid");
        }
    }

    static AgentMcpExecutionContext current() {
        AgentMcpExecutionContext context = CURRENT.get();
        if (context == null) throw new IllegalStateException("MCP call is outside an agent execution");
        return context;
    }

    <T> T call(Callable<T> action) {
        AgentMcpExecutionContext previous = CURRENT.get();
        CURRENT.set(this);
        try {
            return action.call();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Agent MCP execution failed", failure);
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
