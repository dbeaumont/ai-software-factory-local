package com.example.aifactory.sandbox.service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Port implemented by the future privileged GKE Job/Agent Sandbox controller.
 * The MCP server submits only a registered profile; no caller-supplied command is part of this contract.
 */
public interface GkeJobController {
    SandboxRuntime.RuntimeResult run(JobRequest request) throws Exception;

    void cancel(String executionId);

    void reconcileOrphans() throws Exception;

    default void reconcileOrphans(Set<String> retainedExecutionIds) throws Exception {
        reconcileOrphans();
    }

    record JobRequest(
            String executionId,
            String operation,
            String profileId,
            String taskDirectory,
            String image,
            String imageDigest,
            String networkPolicy,
            String cpu,
            String memory,
            int pidsLimit,
            Duration timeout,
            boolean workspaceReadOnly,
            boolean mavenCache,
            List<String> environmentSecretNames) {
        public JobRequest {
            environmentSecretNames = List.copyOf(environmentSecretNames);
        }
    }
}
