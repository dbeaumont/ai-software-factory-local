package com.example.aifactory;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.config.McpClientProperties;
import com.example.aifactory.config.McpFactoryProperties;
import com.example.aifactory.config.ScmDeliveryClientProperties;
import com.example.aifactory.config.AssuranceClientProperties;
import com.example.aifactory.config.AgentToolingProperties;
import com.example.aifactory.config.KillSwitchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AiFactoryProperties.class, McpFactoryProperties.class, McpClientProperties.class,
        ScmDeliveryClientProperties.class, AssuranceClientProperties.class, AgentToolingProperties.class,
        KillSwitchProperties.class})
public class AiFactoryApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiFactoryApplication.class, args);
    }
}
