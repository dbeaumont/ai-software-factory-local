package com.example.aifactory.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public interface McpToolInvoker {
    JsonNode call(String serverName, String toolName, Map<String, Object> arguments);

    Availability availability(String serverName);

    record Availability(boolean available, String error) {
    }
}

