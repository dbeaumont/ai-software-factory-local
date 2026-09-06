package com.example.aifactory.workflow.temporal;

import com.example.aifactory.service.McpToolInvoker;
import com.example.aifactory.workflow.EvidenceRepository;
import io.temporal.activity.ActivityInterface;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DurableExecutionActivitiesTest {
    private static final String COMMIT = "a".repeat(40);

    @Test
    void mapsMcpCallsAndEvidenceStorageWithStableIdentity() throws Exception {
        McpToolInvoker mcp = mock(McpToolInvoker.class);
        EvidenceRepository evidence = mock(EvidenceRepository.class);
        when(mcp.call(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyMap())).thenReturn(new ObjectMapper().readTree("{\"status\":\"ok\"}"));
        byte[] content = "proof".getBytes(StandardCharsets.UTF_8);
        EvidenceRepository.StoreRequest store = new EvidenceRepository.StoreRequest(
                "task-1", "attempt-1", "tests", "text/plain", content, "b".repeat(64), "workflow");
        EvidenceRepository.StoredEvidence stored = new EvidenceRepository.StoredEvidence(
                "evidence://task-1/attempt-1/tests/" + "b".repeat(64), "b".repeat(64), "COMPLETE",
                "text/plain", content.length, "INTERNAL", Instant.now(), Instant.now());
        when(evidence.store(store)).thenReturn(stored);
        DurableExecutionActivitiesImpl activities = new DurableExecutionActivitiesImpl(
                mcp, evidence);
        DurableExecutionActivities.Metadata metadata = metadata();

        activities.invokeMcp(new DurableExecutionActivities.McpCall(
                metadata, "sandbox-execution-mcp", "sandbox.run_tests", Map.of("actor", "workflow")));
        assertThat(activities.storeEvidence(new DurableExecutionActivities.EvidenceCall(metadata, store)))
                .isSameAs(stored);

        @SuppressWarnings("unchecked") ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(mcp).call(org.mockito.ArgumentMatchers.eq("sandbox-execution-mcp"),
                org.mockito.ArgumentMatchers.eq("sandbox.run_tests"), arguments.capture());
        assertThat(arguments.getValue()).containsEntry("idempotency_key", "task-1-attempt-1-tests-1")
                .containsEntry("operation_id", "tests-1")
                .containsEntry("trace_id", metadata.traceId())
                .containsEntry("run_id", metadata.runId())
                .containsEntry("delegation_id", metadata.delegationId())
                .containsEntry("agent_run_id", metadata.agentRunId());
        verify(evidence).store(store);
    }

    @Test
    void exposesATemporalActivityInterface() {
        assertThat(DurableExecutionActivities.class.isAnnotationPresent(ActivityInterface.class)).isTrue();
    }

    private static DurableExecutionActivities.Metadata metadata() {
        return new DurableExecutionActivities.Metadata(
                "task-1", "attempt-1", COMMIT, "tests-1", "task-1-attempt-1-tests-1");
    }
}
