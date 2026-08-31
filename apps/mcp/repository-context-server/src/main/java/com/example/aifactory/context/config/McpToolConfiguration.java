package com.example.aifactory.context.config;

import com.example.aifactory.context.service.RepositoryContextTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfiguration {
    @Bean
    ToolCallbackProvider repositoryContextToolProvider(RepositoryContextTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}

