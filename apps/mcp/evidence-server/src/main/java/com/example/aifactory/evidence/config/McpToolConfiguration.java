package com.example.aifactory.evidence.config;

import com.example.aifactory.evidence.service.EvidenceTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfiguration {
    @Bean ToolCallbackProvider evidenceToolCallbackProvider(EvidenceTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
