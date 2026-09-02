package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import com.example.aifactory.workflow.EvidenceRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextToolHostTest {
    @Test
    void injectsHostMetadataAndRejectsModelControlledIdentity() {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        McpToolInvoker invoker = new McpToolInvoker() {
            public JsonNode call(String server, String tool, Map<String, Object> arguments) {
                captured.set(arguments);
                return new ObjectMapper().createObjectNode().put("ok", true);
            }
            public Availability availability(String server) { return new Availability(true, null); }
        };
        AgentContextToolHost host = new AgentContextToolHost(invoker,
                new McpFactoryProperties(true, McpFactoryProperties.ContextMode.MCP_ACTIVE, "repository-context-mcp"),
                new OperationalKillSwitch((java.nio.file.Path) null));

        host.executor("task-180", "attempt-1", "a".repeat(40), "planner").execute(
                new AgentToolLoop.ToolCall("call", "context.read_file", Map.of("path", "README.md")));

        assertEquals("task-180", captured.get().get("task_id"));
        assertEquals("planner", captured.get().get("actor"));
        assertTrue(captured.get().containsKey("traceparent"));
        assertEquals("attempt-1", captured.get().get("attempt_id"));
        assertThrows(AgentToolLoop.AgentLoopException.class, () -> host.executor(
                "task-180", "attempt-1", "a".repeat(40), "planner").execute(new AgentToolLoop.ToolCall(
                "call", "context.read_file", Map.of("path", "README.md", "actor", "workflow"))));
    }

    @Test
    void routesEvidenceReadsWithWorkflowIdentityAndFixedAuditPurpose() {
        RecordingEvidence evidence = new RecordingEvidence();
        AgentContextToolHost host = new AgentContextToolHost(null,
                new McpFactoryProperties(true, McpFactoryProperties.ContextMode.MCP_ACTIVE,
                        "repository-context-mcp"), new OperationalKillSwitch((java.nio.file.Path) null), evidence);
        String uri = "evidence://task-180/attempt-1/review/" + "b".repeat(64);

        String summary = host.executor("task-180", "attempt-1", "a".repeat(40), "security-agent")
                .execute(new AgentToolLoop.ToolCall("summary", "evidence.get_summary", Map.of("uri", uri)));
        String raw = host.executor("task-180", "attempt-1", "a".repeat(40), "independent-reviewer")
                .execute(new AgentToolLoop.ToolCall("raw", "evidence.read", Map.of("uri", uri)));

        assertTrue(summary.contains("\"digest\":\"" + "b".repeat(64) + "\""));
        assertTrue(raw.contains("\"content\""));
        assertEquals("security-agent", evidence.summaryActor);
        assertEquals("attempt-1", evidence.readRequest.attemptId());
        assertEquals("independent-reviewer", evidence.readRequest.actor());
        assertEquals("human-review", evidence.readRequest.purpose());
        assertTrue(host.definitions().stream().map(LlmGatewayClient.ToolDefinition::name).toList()
                .containsAll(java.util.List.of("evidence.get_summary", "evidence.read")));
    }

    private static final class RecordingEvidence implements EvidenceRepository {
        private String summaryActor;
        private ReadRequest readRequest;

        @Override public EvidenceSummary getSummary(String taskId, String attemptId, String uri, String actor) {
            summaryActor = actor;
            return new EvidenceSummary(uri, "review", "b".repeat(64), "COMPLETE", "CONFIDENTIAL", 5);
        }

        @Override public RawEvidence read(ReadRequest request) {
            readRequest = request;
            return new RawEvidence(request.uri(), "review", "b".repeat(64),
                    "COMPLETE", "CONFIDENTIAL", "proof".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override public StoredEvidence store(StoreRequest request) { throw new UnsupportedOperationException(); }
        @Override public StoredManifest createManifest(ManifestRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
