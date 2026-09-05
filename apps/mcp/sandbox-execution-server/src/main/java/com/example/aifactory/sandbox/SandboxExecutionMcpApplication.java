package com.example.aifactory.sandbox;

import com.example.aifactory.sandbox.config.SandboxExecutionProperties;
import com.example.aifactory.sandbox.config.SandboxDependencyProperties;
import com.example.aifactory.sandbox.config.ComposeSandboxProperties;
import com.example.aifactory.sandbox.config.GkeControllerProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

@SpringBootApplication
@EnableConfigurationProperties({SandboxExecutionProperties.class, SandboxDependencyProperties.class,
        ComposeSandboxProperties.class, GkeControllerProperties.class})
public class SandboxExecutionMcpApplication {
    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(SandboxExecutionMcpApplication.class, args);
    }
}
