package com.example.aifactory.context.service;

import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Component;

@Component
public class RepositoryResources {
    private final RepositoryContextTools tools;

    public RepositoryResources(RepositoryContextTools tools) {
        this.tools = tools;
    }

    @McpResource(
            uri = "repo://{task_id}/{source_commit}/{path}",
            name = "Immutable repository file",
            description = "Read a bounded, redacted UTF-8 file pinned to a registered task and source commit",
            mimeType = "text/plain")
    public String readRepositoryFile(String task_id, String source_commit, String path) throws Exception {
        String decodedPath = java.net.URLDecoder.decode(path, java.nio.charset.StandardCharsets.UTF_8);
        return tools.readRegisteredResource(task_id, source_commit, decodedPath);
    }
}
