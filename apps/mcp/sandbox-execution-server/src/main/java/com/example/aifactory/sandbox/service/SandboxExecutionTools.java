package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.model.SandboxModels.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

@Service
public class SandboxExecutionTools {
    private final SandboxJobService jobs;

    public SandboxExecutionTools(SandboxJobService jobs) {
        this.jobs = jobs;
    }

    @Tool(name = "sandbox.validate_patch", description = "Start immutable profile patch-check-v1; no network and no caller-supplied command")
    public ExecutionView validatePatch(
            @ToolParam(description = "Contract version, currently 1") String schema_version,
            @ToolParam(description = "Registered task identifier") String task_id,
            @ToolParam(description = "Immutable source commit SHA") String source_commit,
            @ToolParam(description = "Authorized caller; workflow only") String actor,
            @ToolParam(description = "32-character trace identifier") String trace_id,
            @ToolParam(description = "Stable command idempotency key") String idempotency_key,
            @ToolParam(description = "SHA-256 of changes.patch") String patch_digest) throws Exception {
        return start(Operation.VALIDATE_PATCH, schema_version, task_id, source_commit, actor, trace_id,
                idempotency_key, patch_digest);
    }

    @Tool(name = "sandbox.apply_patch", description = "Start immutable profile patch-apply-v1; no network and no caller-supplied command")
    public ExecutionView applyPatch(
            @ToolParam(description = "Contract version, currently 1") String schema_version,
            @ToolParam(description = "Registered task identifier") String task_id,
            @ToolParam(description = "Immutable source commit SHA") String source_commit,
            @ToolParam(description = "Authorized caller; workflow only") String actor,
            @ToolParam(description = "32-character trace identifier") String trace_id,
            @ToolParam(description = "Stable command idempotency key") String idempotency_key,
            @ToolParam(description = "SHA-256 of changes.patch") String patch_digest) throws Exception {
        return start(Operation.APPLY_PATCH, schema_version, task_id, source_commit, actor, trace_id,
                idempotency_key, patch_digest);
    }

    @Tool(name = "sandbox.run_tests", description = "Start immutable profile test-auto-v1 with bounded resources and server-side build configuration")
    public ExecutionView runTests(
            @ToolParam(description = "Contract version, currently 1") String schema_version,
            @ToolParam(description = "Registered task identifier") String task_id,
            @ToolParam(description = "Immutable source commit SHA") String source_commit,
            @ToolParam(description = "Authorized caller; workflow only") String actor,
            @ToolParam(description = "32-character trace identifier") String trace_id,
            @ToolParam(description = "Stable command idempotency key") String idempotency_key,
            @ToolParam(required = false, description = "Optional SHA-256 of changes.patch") String patch_digest) throws Exception {
        return start(Operation.RUN_TESTS, schema_version, task_id, source_commit, actor, trace_id,
                idempotency_key, patch_digest);
    }

    @Tool(name = "sandbox.run_quality", description = "Start immutable profile quality-sonar-v1 with secrets injected only by the server")
    public ExecutionView runQuality(
            @ToolParam(description = "Contract version, currently 1") String schema_version,
            @ToolParam(description = "Registered task identifier") String task_id,
            @ToolParam(description = "Immutable source commit SHA") String source_commit,
            @ToolParam(description = "Authorized caller; workflow only") String actor,
            @ToolParam(description = "32-character trace identifier") String trace_id,
            @ToolParam(description = "Stable command idempotency key") String idempotency_key,
            @ToolParam(required = false, description = "Optional SHA-256 of changes.patch") String patch_digest) throws Exception {
        return start(Operation.RUN_QUALITY, schema_version, task_id, source_commit, actor, trace_id,
                idempotency_key, patch_digest);
    }

    @Tool(name = "sandbox.run_security", description = "Start immutable profile security-syft-trivy-v1 with bounded resources")
    public ExecutionView runSecurity(
            @ToolParam(description = "Contract version, currently 1") String schema_version,
            @ToolParam(description = "Registered task identifier") String task_id,
            @ToolParam(description = "Immutable source commit SHA") String source_commit,
            @ToolParam(description = "Authorized caller; workflow only") String actor,
            @ToolParam(description = "32-character trace identifier") String trace_id,
            @ToolParam(description = "Stable command idempotency key") String idempotency_key,
            @ToolParam(required = false, description = "Optional SHA-256 of changes.patch") String patch_digest) throws Exception {
        return start(Operation.RUN_SECURITY, schema_version, task_id, source_commit, actor, trace_id,
                idempotency_key, patch_digest);
    }

    @Tool(name = "sandbox.get_execution", description = "Read the bounded, redacted state of a task-owned sandbox execution")
    public ExecutionView getExecution(
            @ToolParam(description = "Contract version, currently 1") String schema_version,
            @ToolParam(description = "Registered task identifier") String task_id,
            @ToolParam(description = "Immutable source commit SHA") String source_commit,
            @ToolParam(description = "Authorized caller; workflow only") String actor,
            @ToolParam(description = "32-character trace identifier") String trace_id,
            @ToolParam(description = "Opaque execution handle") String execution_id) {
        return jobs.get(schema_version, task_id, source_commit, actor, trace_id, execution_id);
    }

    @Tool(name = "sandbox.cancel_execution", description = "Cancel a task-owned sandbox execution and clean up its container")
    public ExecutionView cancelExecution(
            @ToolParam(description = "Contract version, currently 1") String schema_version,
            @ToolParam(description = "Registered task identifier") String task_id,
            @ToolParam(description = "Immutable source commit SHA") String source_commit,
            @ToolParam(description = "Authorized caller; workflow only") String actor,
            @ToolParam(description = "32-character trace identifier") String trace_id,
            @ToolParam(description = "Opaque execution handle") String execution_id) {
        return jobs.cancel(schema_version, task_id, source_commit, actor, trace_id, execution_id);
    }

    private ExecutionView start(Operation operation, String schemaVersion, String taskId, String sourceCommit,
                                String actor, String traceId, String idempotencyKey, String patchDigest) throws Exception {
        return jobs.submit(operation, new StartExecutionRequest(schemaVersion, taskId, sourceCommit, actor,
                traceId, idempotencyKey, patchDigest));
    }
}
