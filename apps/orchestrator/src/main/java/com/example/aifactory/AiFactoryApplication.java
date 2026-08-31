package com.example.aifactory;

import com.example.aifactory.config.AiFactoryProperties;
import com.example.aifactory.config.McpFactoryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AiFactoryProperties.class, McpFactoryProperties.class})
public class AiFactoryApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiFactoryApplication.class, args);
    }
}
