package com.example.aifactory.service;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service("validatedMcpToolInvoker")
public class ValidatedMcpToolInvoker implements McpToolInvoker {
    private final SpringMcpToolInvoker delegate;
    private final McpResponseValidator validator;

    public ValidatedMcpToolInvoker(SpringMcpToolInvoker delegate, McpResponseValidator validator) {
        this.delegate = delegate;
        this.validator = validator;
    }

    @Override
    public JsonNode call(String serverName, String toolName, Map<String, Object> arguments) {
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
