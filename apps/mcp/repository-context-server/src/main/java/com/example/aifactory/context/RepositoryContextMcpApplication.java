package com.example.aifactory.context;

import com.example.aifactory.context.config.RepositoryContextProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RepositoryContextProperties.class)
public class RepositoryContextMcpApplication {
    public static void main(String[] args) {
        SpringApplication.run(RepositoryContextMcpApplication.class, args);
    }
}

