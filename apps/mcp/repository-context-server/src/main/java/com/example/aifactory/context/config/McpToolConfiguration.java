package com.example.aifactory.context.config;

import com.example.aifactory.context.service.RepositoryContextTools;
import com.example.aifactory.context.service.RepositorySymbolTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfiguration {
    @Bean
    ToolCallbackProvider repositoryContextToolProvider(RepositoryContextTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai-factory.context.symbols", name = "enabled", havingValue = "true")
    RepositorySymbolTools repositorySymbolTools(RepositoryContextTools contextTools) {
        return new RepositorySymbolTools(contextTools);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ai-factory.context.symbols", name = "enabled", havingValue = "true")
    ToolCallbackProvider repositorySymbolToolProvider(RepositorySymbolTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
