package com.example.aifactory.context;

import com.example.aifactory.context.config.RepositoryContextProperties;
import com.example.aifactory.context.config.McpRoleSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({RepositoryContextProperties.class, McpRoleSecurityProperties.class})
public class RepositoryContextMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(RepositoryContextMcpApplication.class, args);
    }
}
