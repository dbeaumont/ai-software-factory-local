package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.AgentRuntime;
import com.example.aifactory.workflow.EvidenceRepository;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

import java.util.LinkedHashMap;
import java.util.Map;

@ActivityInterface
public interface DurableExecutionActivities {
    @ActivityMethod(name = "InvokeAgent")
    AgentResult invokeAgent(AgentCall call);

    @ActivityMethod(name = "InvokeMcpTool")
    McpResult invokeMcp(McpCall call);

    @ActivityMethod(name = "StoreEvidence")
    EvidenceRepository.StoredEvidence storeEvidence(EvidenceCall call);

    record Metadata(String taskId, String attemptId, String sourceCommit, String operationId,
                    String idempotencyKey) {
        public Metadata {
            if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,64}")
                    || attemptId == null || !attemptId.matches("[A-Za-z0-9_-]{1,128}")
                    || sourceCommit == null || !sourceCommit.matches("[0-9a-f]{40}")
                    || operationId == null || !operationId.matches("[A-Za-z0-9_-]{1,128}")
                    || idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{8,200}")) {
                throw new IllegalArgumentException("Activity metadata is invalid or not idempotent");
            }
        }
    }

    record AgentCall(Metadata metadata, AgentRuntime.Invocation invocation) {}

    record AgentResult(String document, String promptFingerprint, int turns, int tokens, long costMicros) {}

    record McpCall(Metadata metadata, String server, String tool, Map<String, Object> arguments) {
        public McpCall {
            arguments = arguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(arguments));
        }
    }

    record McpResult(String document) {}

    record EvidenceCall(Metadata metadata, EvidenceRepository.StoreRequest request) {}
}
