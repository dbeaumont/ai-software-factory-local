package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class McpFactoryPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "ai-factory.mcp.enabled=false",
                    "ai-factory.mcp.repository-context-mode=DIRECT",
                    "ai-factory.mcp.repository-context-server-name=repository-context-mcp",
                    "ai-factory.mcp.sandbox-enabled=true",
                    "ai-factory.mcp.sandbox-mode=MCP_ACTIVE",
                    "ai-factory.mcp.sandbox-active-operations=validate_patch,run_tests",
                    "ai-factory.mcp.sandbox-server-name=sandbox-execution-mcp",
                    "ai-factory.mcp.sandbox-poll-interval=250ms",
                    "ai-factory.mcp.sandbox-poll-timeout=20m");

    @Test
    void bindsTheCanonicalConstructorWhenConvenienceConstructorAlsoExists() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            McpFactoryProperties properties = context.getBean(McpFactoryProperties.class);
            assertThat(properties.sandboxEnabled()).isTrue();
            assertThat(properties.sandboxMode()).isEqualTo(McpFactoryProperties.SandboxMode.MCP_ACTIVE);
            assertThat(properties.sandboxActiveOperations()).containsExactlyInAnyOrder("validate_patch", "run_tests");
            assertThat(properties.sandboxPollInterval()).isEqualTo(Duration.ofMillis(250));
            assertThat(properties.sandboxPollTimeout()).isEqualTo(Duration.ofMinutes(20));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(McpFactoryProperties.class)
    static class PropertiesConfiguration {
    }
}
