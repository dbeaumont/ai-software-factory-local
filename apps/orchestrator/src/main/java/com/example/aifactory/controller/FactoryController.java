package com.example.aifactory.controller;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.config.McpFactoryProperties;
import com.example.aifactory.model.FactoryCapabilities;
import com.example.aifactory.service.LlmGatewayClient;
import com.example.aifactory.service.McpRepositoryContextService;
import com.example.aifactory.service.McpSandboxService;
import com.example.aifactory.service.AdmissionControl;
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
    private final AdmissionControl admissionControl;

    public FactoryController(AiFactoryProperties props,
                             McpFactoryProperties mcpProperties,
                             LlmGatewayClient llm,
                             McpRepositoryContextService repositoryContextMcp,
                             McpSandboxService sandboxMcp,
                             AdmissionControl admissionControl) {
        this.props = props;
        this.mcpProperties = mcpProperties;
        this.llm = llm;
        this.repositoryContextMcp = repositoryContextMcp;
        this.sandboxMcp = sandboxMcp;
        this.admissionControl = admissionControl;
    }

    @GetMapping("/capabilities")
    public Mono<FactoryCapabilities> capabilities() {
        return Mono.zip(llm.cloudAvailabilityAsync(), Mono.fromCallable(admissionControl::status)
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()))
                .map(values -> {
                    var cloud = values.getT1();
                    var admission = values.getT2();
                    McpRepositoryContextService.Availability mcp = repositoryContextMcp.availability();
                    McpSandboxService.Availability sandbox = sandboxMcp.availability();
                    return new FactoryCapabilities(props.cloudEnabled(), cloud.available(), cloud.error(),
                            mcpProperties.enabled(), mcp.available(), safeMcpError(mcp),
                            mcpProperties.sandboxEnabled(), sandbox.available(), safeSandboxError(sandbox),
                            admission.admissionsOpen(), admission.reason(), admission.revision());
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
