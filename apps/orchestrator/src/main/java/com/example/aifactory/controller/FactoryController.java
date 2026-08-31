package com.example.aifactory.controller;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.config.McpFactoryProperties;
import com.example.aifactory.model.FactoryCapabilities;
import com.example.aifactory.service.LlmGatewayClient;
import com.example.aifactory.service.McpRepositoryContextService;
import com.example.aifactory.service.McpSandboxService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api")
public class FactoryController {
    private final AiFactoryProperties props;
    private final McpFactoryProperties mcpProperties;
    private final LlmGatewayClient llm;
    private final McpRepositoryContextService repositoryContextMcp;
    private final McpSandboxService sandboxMcp;

    public FactoryController(AiFactoryProperties props,
                             McpFactoryProperties mcpProperties,
                             LlmGatewayClient llm,
                             McpRepositoryContextService repositoryContextMcp,
                             McpSandboxService sandboxMcp) {
        this.props = props;
        this.mcpProperties = mcpProperties;
        this.llm = llm;
        this.repositoryContextMcp = repositoryContextMcp;
        this.sandboxMcp = sandboxMcp;
    }

    @GetMapping("/capabilities")
    public Mono<FactoryCapabilities> capabilities() {
        return llm.cloudAvailabilityAsync()
                .map(cloud -> {
                    McpRepositoryContextService.Availability mcp = repositoryContextMcp.availability();
                    McpSandboxService.Availability sandbox = sandboxMcp.availability();
                    return new FactoryCapabilities(props.cloudEnabled(), cloud.available(), cloud.error(),
                            mcpProperties.enabled(), mcp.available(), safeMcpError(mcp),
                            mcpProperties.sandboxEnabled(), sandbox.available(), safeSandboxError(sandbox));
                });
    }

    private String safeMcpError(McpRepositoryContextService.Availability availability) {
        return mcpProperties.enabled() && !availability.available()
                ? "repository-context-mcp unavailable"
                : null;
    }

    private String safeSandboxError(McpSandboxService.Availability availability) {
        return mcpProperties.sandboxEnabled() && !availability.available()
                ? "sandbox-execution-mcp unavailable"
                : null;
    }
}
