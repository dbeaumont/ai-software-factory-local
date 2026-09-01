package com.example.aifactory.assurance.config;

import com.example.aifactory.assurance.service.AssuranceTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfiguration {
    @Bean
    ToolCallbackProvider assuranceToolCallbackProvider(AssuranceTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
