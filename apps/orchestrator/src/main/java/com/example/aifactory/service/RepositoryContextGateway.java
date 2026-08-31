package com.example.aifactory.service;

import com.example.aifactory.config.McpFactoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Primary
@Service
public class RepositoryContextGateway implements RepositoryContextProvider {
    private static final Logger log = LoggerFactory.getLogger(RepositoryContextGateway.class);

    private final RepositoryContextService direct;
    private final McpContextProvider mcp;
    private final McpFactoryProperties properties;

    public RepositoryContextGateway(RepositoryContextService direct,
                                    McpContextProvider mcp,
                                    McpFactoryProperties properties) {
        this.direct = direct;
        this.mcp = mcp;
        this.properties = properties;
    }

    @Override
    public String collect(Path repository, String taskId, String sourceCommit) throws Exception {
        if (properties.repositoryContextMode() == McpFactoryProperties.ContextMode.MCP_ACTIVE) {
            if (!properties.enabled()) {
                throw new IllegalStateException("MCP context mode is active but MCP is disabled");
            }
            return mcp.collect(repository, taskId, sourceCommit);
        }
        String directContext = direct.collect(repository, taskId, sourceCommit);
        if (!properties.enabled() || properties.repositoryContextMode() == McpFactoryProperties.ContextMode.DIRECT) {
            return directContext;
        }
        try {
            String mcpContext = mcp.collect(repository, taskId, sourceCommit);
            log.info("MCP shadow context task={}: direct_chars={}, mcp_chars={}, equal={}",
                    taskId, directContext.length(), mcpContext.length(), directContext.equals(mcpContext));
        } catch (RuntimeException exception) {
            log.warn("MCP shadow context failed for task={}: {}", taskId, exception.getMessage());
        }
        return directContext;
    }
}
