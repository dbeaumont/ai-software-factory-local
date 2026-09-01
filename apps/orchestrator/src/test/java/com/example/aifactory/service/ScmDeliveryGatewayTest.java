package com.example.aifactory.service;

import com.example.aifactory.config.ScmDeliveryClientProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScmDeliveryGatewayTest {
    @Test
    void signsEvidenceAndInvokesOnlyAtomicDeliveryTool(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("changes.patch"), "patch");
        Files.writeString(workspace.resolve(".ai-plan.md"), "plan");
        Files.writeString(workspace.resolve(".ai-review.md"), "review");
        Files.createDirectories(workspace.resolve(".ai-factory"));
        Files.writeString(workspace.resolve(".ai-factory/test.txt"), "tests");
        Files.writeString(workspace.resolve(".ai-factory/sonar.txt"), "quality");
        Files.writeString(workspace.resolve(".ai-factory/sbom.cdx.json"), "{}");
        Files.writeString(workspace.resolve(".ai-factory/trivy.txt"), "security");
        ObjectMapper mapper = new ObjectMapper();
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        McpToolInvoker invoker = new McpToolInvoker() {
            @Override
            public JsonNode call(String serverName, String toolName, Map<String, Object> arguments) {
                assertEquals("scm-delivery-mcp", serverName);
                assertEquals("scm.create_draft_pull_request", toolName);
                captured.set(arguments);
                return mapper.valueToTree(Map.of(
                        "repositoryId", "customer-api", "branch", "ai-factory/task-1-approval-1",
                        "commit", "b".repeat(40), "pullRequestId", 7,
                        "pullRequestUrl", "http://localhost:3000/aiadmin/customer-api/pulls/7", "draft", true));
            }

            @Override
            public Availability availability(String serverName) {
                return new Availability(true, null);
            }
        };
        McpResponseValidator validator = mock(McpResponseValidator.class);
        when(validator.validate(eq("scm.create_draft_pull_request"), any())).thenAnswer(call -> call.getArgument(1));
        String key = "approval-key-for-tests-at-least-32-bytes";
        ScmDeliveryGateway gateway = new ScmDeliveryGateway(invoker, validator,
                new ScmDeliveryClientProperties(true, "scm-delivery-mcp", "David Beaumont", key));

        String url = gateway.createDraftPullRequest(workspace, "http://gitea:3000/aiadmin/customer-api.git",
                "main", "task-1", "a".repeat(40), "Test delivery");

        assertEquals("http://localhost:3000/aiadmin/customer-api/pulls/7", url);
        assertEquals("customer-api", captured.get().get("repository_id"));
        assertEquals("delivery-task-1-approval-1", captured.get().get("idempotency_key"));
        assertFalse(captured.get().toString().contains(key));
    }
}
