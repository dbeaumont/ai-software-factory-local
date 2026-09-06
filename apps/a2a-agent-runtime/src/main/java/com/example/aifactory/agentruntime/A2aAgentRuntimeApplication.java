package com.example.aifactory.agentruntime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AgentRuntimeProperties.class, LlmAdapterProperties.class, AgentMcpProperties.class,
        A2aSecurityProperties.class, A2aCardSigningProperties.class, A2aCardIdentityProperties.class})
public class A2aAgentRuntimeApplication {
    public static void main(String[] args) {
        SpringApplication.run(A2aAgentRuntimeApplication.class, args);
    }
}
