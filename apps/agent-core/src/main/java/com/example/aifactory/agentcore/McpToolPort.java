package com.example.aifactory.agentcore;

import java.util.List;
import java.util.Map;

/** Role-scoped MCP boundary. Implementations must authorize every call, not only client construction. */
public interface McpToolPort {
    List<LlmCompletionPort.ToolDefinition> definitions();
    String call(String toolName, Map<String, Object> arguments);
}
