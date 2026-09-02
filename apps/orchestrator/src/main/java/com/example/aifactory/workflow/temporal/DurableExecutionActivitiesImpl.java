package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.AgentRuntime;
import com.example.aifactory.service.McpToolInvoker;
import com.example.aifactory.service.ExecutionTracer;
import com.example.aifactory.workflow.EvidenceRepository;

import java.util.LinkedHashMap;
import java.util.Map;

/** Activity adapter registered by a worker once the corresponding execution mode is enabled. */
public final class DurableExecutionActivitiesImpl implements DurableExecutionActivities {
    private final AgentRuntime agents;
    private final McpToolInvoker mcp;
    private final EvidenceRepository evidence;
    private final ExecutionTracer tracer;

    public DurableExecutionActivitiesImpl(AgentRuntime agents, McpToolInvoker mcp, EvidenceRepository evidence) {
        this(agents, mcp, evidence, ExecutionTracer.noop());
    }

    public DurableExecutionActivitiesImpl(AgentRuntime agents, McpToolInvoker mcp, EvidenceRepository evidence,
                                          ExecutionTracer tracer) {
        this.agents = agents;
        this.mcp = mcp;
        this.evidence = evidence;
        this.tracer = tracer;
    }

    @Override
    public AgentResult invokeAgent(AgentCall call) {
        return tracer.trace(ExecutionTracer.SpanKind.ACTIVITY, call.metadata().executionIdentity(),
                "InvokeAgent", () -> invokeAgentObserved(call));
    }

    private AgentResult invokeAgentObserved(AgentCall call) {
        requireBound(call.metadata(), call.invocation().taskId(), call.invocation().attemptId(),
                call.invocation().sourceCommit());
        if (!call.metadata().executionIdentity().equals(call.invocation().executionIdentity())) {
            throw new IllegalArgumentException("Agent invocation is not bound to its workflow correlation identity");
        }
        AgentRuntime.Result result = agents.execute(call.invocation());
        return new AgentResult(result.document().toString(), result.promptFingerprint(),
                result.turns(), result.tokens(), result.costMicros());
    }

    @Override
    public McpResult invokeMcp(McpCall call) {
        return tracer.trace(ExecutionTracer.SpanKind.ACTIVITY, call.metadata().executionIdentity(),
                "InvokeMcpTool", () -> invokeMcpObserved(call));
    }

    private McpResult invokeMcpObserved(McpCall call) {
        Map<String, Object> arguments = new LinkedHashMap<>(call.arguments());
        arguments.put("task_id", call.metadata().taskId());
        arguments.put("attempt_id", call.metadata().attemptId());
        arguments.put("source_commit", call.metadata().sourceCommit());
        arguments.put("operation_id", call.metadata().operationId());
        arguments.put("idempotency_key", call.metadata().idempotencyKey());
        arguments.put("trace_id", call.metadata().traceId());
        arguments.put("run_id", call.metadata().runId());
        arguments.put("delegation_id", call.metadata().delegationId());
        arguments.put("agent_run_id", call.metadata().agentRunId());
        return new McpResult(mcp.call(call.server(), call.tool(), Map.copyOf(arguments)).toString());
    }

    @Override
    public EvidenceRepository.StoredEvidence storeEvidence(EvidenceCall call) {
        return tracer.trace(ExecutionTracer.SpanKind.ACTIVITY, call.metadata().executionIdentity(),
                "StoreEvidence", () -> storeEvidenceObserved(call));
    }

    private EvidenceRepository.StoredEvidence storeEvidenceObserved(EvidenceCall call) {
        EvidenceRepository.StoreRequest request = call.request();
        requireBound(call.metadata(), request.taskId(), request.attemptId(), call.metadata().sourceCommit());
        return evidence.store(request);
    }

    private static void requireBound(Metadata metadata, String taskId, String attemptId, String sourceCommit) {
        if (!metadata.taskId().equals(taskId) || !metadata.attemptId().equals(attemptId)
                || !metadata.sourceCommit().equals(sourceCommit)) {
            throw new IllegalArgumentException("Activity payload is not bound to its workflow metadata");
        }
    }
}
