package com.example.aifactory.agentcore;

import java.util.List;
import java.util.Map;

/** Provider-neutral model completion boundary used by an agent runtime. */
@FunctionalInterface
public interface LlmCompletionPort {
    AgentLoop.Turn nextTurn(List<AgentLoop.Message> messages, List<ToolDefinition> tools, int maxOutputTokens);

    record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        public ToolDefinition {
            if (name == null || !name.matches("[a-z][a-z0-9_-]{0,63}\\.[a-z][a-z0-9_-]{0,63}")) {
                throw new IllegalArgumentException("Tool name must be namespaced");
            }
            description = description == null ? "" : description;
            inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
        }
    }
}
