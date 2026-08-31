package com.example.aifactory.sandbox.config;

import com.example.aifactory.sandbox.service.SandboxExecutionTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfiguration {
    @Bean
    ToolCallbackProvider sandboxTools(SandboxExecutionTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
