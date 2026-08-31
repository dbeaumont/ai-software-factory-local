package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Primary
@Service
public class SandboxGateway implements SandboxExecutor {
    private static final Logger log = LoggerFactory.getLogger(SandboxGateway.class);

    private final SandboxService direct;
    private final McpSandboxService mcp;
    private final McpFactoryProperties properties;

    public SandboxGateway(SandboxService direct, McpSandboxService mcp, McpFactoryProperties properties) {
        this.direct = direct;
        this.mcp = mcp;
        this.properties = properties;
    }

    @Override
    public String applyPatch(Path workspace, String taskId, String sourceCommit) throws Exception {
        if (active()) {
            return mcp.applyPatch(workspace, taskId, sourceCommit);
        }
        String result = direct.applyPatch(workspace, taskId, sourceCommit);
        if (shadow()) {
            log.info("Sandbox shadow skips duplicate apply_patch for task={} because the operation mutates the shared workspace", taskId);
        }
        return result;
    }

    @Override
    public String checkPatch(Path workspace, String taskId, String sourceCommit) throws Exception {
        return executeComparable("validate_patch", workspace, taskId, sourceCommit,
                direct::checkPatch, mcp::checkPatch);
    }

    @Override
    public String test(Path workspace, String taskId, String sourceCommit) throws Exception {
        return executeComparable("run_tests", workspace, taskId, sourceCommit, direct::test, mcp::test);
    }

    @Override
    public String quality(Path workspace, String taskId, String sourceCommit) throws Exception {
        return executeComparable("run_quality", workspace, taskId, sourceCommit, direct::quality, mcp::quality);
    }

    @Override
    public String security(Path workspace, String taskId, String sourceCommit) throws Exception {
        return executeComparable("run_security", workspace, taskId, sourceCommit, direct::security, mcp::security);
    }

    private String executeComparable(String operation, Path workspace, String taskId, String sourceCommit,
                                     SandboxCall directCall, SandboxCall mcpCall) throws Exception {
        if (active()) {
            return mcpCall.call(workspace, taskId, sourceCommit);
        }
        String directResult = directCall.call(workspace, taskId, sourceCommit);
        if (shadow()) {
            try {
                String mcpResult = mcpCall.call(workspace, taskId, sourceCommit);
                log.info("Sandbox shadow operation={} task={}: direct_chars={}, mcp_chars={}, equal={}",
                        operation, taskId, directResult.length(), mcpResult.length(), directResult.equals(mcpResult));
            } catch (Exception exception) {
                log.warn("Sandbox shadow operation={} failed for task={}: {}", operation, taskId, exception.getMessage());
            }
        }
        return directResult;
    }

    private boolean active() {
        if (properties.sandboxMode() != McpFactoryProperties.SandboxMode.MCP_ACTIVE) {
            return false;
        }
        if (!properties.sandboxEnabled()) {
            throw new IllegalStateException("sandbox MCP mode is active but sandbox MCP is disabled");
        }
        return true;
    }

    private boolean shadow() {
        return properties.sandboxEnabled()
                && properties.sandboxMode() == McpFactoryProperties.SandboxMode.MCP_SHADOW;
    }

    @FunctionalInterface
    private interface SandboxCall {
        String call(Path workspace, String taskId, String sourceCommit) throws Exception;
    }
}
