package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Primary
@Service
public class SandboxGateway implements SandboxExecutor {
    private final McpSandboxService mcp;
    private final McpFactoryProperties properties;

    public SandboxGateway(McpSandboxService mcp, McpFactoryProperties properties) {
        this.mcp = mcp;
        this.properties = properties;
    }

    @Override
    public String applyPatch(Path workspace, String taskId, String sourceCommit) throws Exception {
        requireActive("apply_patch");
        return mcp.applyPatch(workspace, taskId, sourceCommit);
    }

    @Override
    public String checkPatch(Path workspace, String taskId, String sourceCommit) throws Exception {
        requireActive("validate_patch");
        return mcp.checkPatch(workspace, taskId, sourceCommit);
    }

    @Override
    public String test(Path workspace, String taskId, String sourceCommit) throws Exception {
        requireActive("run_tests");
        return mcp.test(workspace, taskId, sourceCommit);
    }

    @Override
    public String quality(Path workspace, String taskId, String sourceCommit) throws Exception {
        requireActive("run_quality");
        return mcp.quality(workspace, taskId, sourceCommit);
    }

    @Override
    public String security(Path workspace, String taskId, String sourceCommit) throws Exception {
        requireActive("run_security");
        return mcp.security(workspace, taskId, sourceCommit);
    }

    private void requireActive(String operation) {
        if (properties.sandboxMode() != McpFactoryProperties.SandboxMode.MCP_ACTIVE) {
            throw new IllegalStateException("sandbox direct/shadow modes were retired; MCP_ACTIVE is required");
        }
        if (!properties.sandboxOperationActive(operation)) {
            throw new IllegalStateException("sandbox MCP operation is disabled: " + operation);
        }
        if (!properties.sandboxEnabled()) {
            throw new IllegalStateException("sandbox MCP mode is active but sandbox MCP is disabled");
        }
    }
}
