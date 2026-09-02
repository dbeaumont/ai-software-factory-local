package com.example.aifactory.service;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service("validatedMcpToolInvoker")
public class ValidatedMcpToolInvoker implements McpToolInvoker {
    private final SpringMcpToolInvoker delegate;
    private final McpResponseValidator validator;
    private final OperationalKillSwitch killSwitch;

    public ValidatedMcpToolInvoker(SpringMcpToolInvoker delegate, McpResponseValidator validator,
                                   OperationalKillSwitch killSwitch) {
        this.delegate = delegate;
        this.validator = validator;
        this.killSwitch = killSwitch;
    }

    @Override
    public JsonNode call(String serverName, String toolName, Map<String, Object> arguments) {
        OperationalKillSwitch.Decision decision = killSwitch.decision(serverName, toolName, "workflow",
                String.valueOf(arguments.getOrDefault("execution_mode", "PIPELINE")));
        if (!decision.allowed()) {
            throw new McpInvocationException("KILL_SWITCH", false,
                    "MCP invocation disabled by operations: " + decision.reason());
        }
        return validator.validate(toolName, delegate.call(serverName, toolName, arguments));
    }

    @Override
    public Availability availability(String serverName) {
        return delegate.availability(serverName);
    }

    @Override
    public ServerDescriptor describe(String serverName) {
        return delegate.describe(serverName);
    }
}
