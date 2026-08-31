package com.example.aifactory.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Set;

public interface McpToolInvoker {
    JsonNode call(String serverName, String toolName, Map<String, Object> arguments);

    Availability availability(String serverName);

    default ServerDescriptor describe(String serverName) {
        Availability availability = availability(serverName);
        return new ServerDescriptor(
                availability.available(), null, null, null, Set.of(), availability.error());
    }

    record Availability(boolean available, String error) {
    }

    record ServerDescriptor(
            boolean available,
            String protocolVersion,
            String name,
            String version,
            Set<String> tools,
            String error) {
        public ServerDescriptor {
            tools = tools == null ? Set.of() : Set.copyOf(tools);
        }
    }
}
