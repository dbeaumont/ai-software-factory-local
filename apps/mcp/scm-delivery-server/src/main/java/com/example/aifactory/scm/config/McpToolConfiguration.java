package com.example.aifactory.scm.config;

import com.example.aifactory.scm.service.ScmDeliveryTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class McpToolConfiguration {
    @Bean
    ToolCallbackProvider scmDeliveryTools(ScmDeliveryTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
