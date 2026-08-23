package com.example.aifactory;

import com.example.aifactory.config.AiFactoryProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AiFactoryProperties.class)
public class AiFactoryApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiFactoryApplication.class, args);
    }
}
