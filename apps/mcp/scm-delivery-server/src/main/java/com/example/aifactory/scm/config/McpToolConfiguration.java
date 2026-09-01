package com.example.aifactory.scm.config;

import com.example.aifactory.scm.service.ScmDeliveryTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class McpToolConfiguration {
    @Bean
    WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }

    @Bean
    ToolCallbackProvider scmDeliveryToolCallbackProvider(ScmDeliveryTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
