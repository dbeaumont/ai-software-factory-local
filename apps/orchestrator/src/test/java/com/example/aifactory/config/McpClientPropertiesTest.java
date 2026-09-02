package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class McpClientPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(validProperties());

    @Test
    void bindsServersLimitsTimeoutsAudiencesAndRetryPolicies() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            McpClientProperties properties = context.getBean(McpClientProperties.class);
            assertThat(properties.enabled()).isTrue();
            assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(20));
            assertThat(properties.maxResponseBytes()).isEqualTo(65_536);
            assertThat(properties.maxInflightPerServer()).isEqualTo(16);
            assertThat(properties.maxInflightPerTask()).isEqualTo(4);
            assertThat(properties.acceptedProtocolVersions())
                    .containsExactlyInAnyOrder("2025-11-25", "2025-06-18");
            assertThat(properties.retry().readOnly().maxAttempts()).isEqualTo(3);
            assertThat(properties.retry().effectful().maxAttempts()).isEqualTo(2);
            assertThat(properties.servers()).containsOnlyKeys(
                    "repository-context", "sandbox-execution", "evidence");
            assertThat(properties.servers().get("repository-context").uri())
                    .isEqualTo(URI.create("http://repository-context-mcp:8091"));
            assertThat(properties.servers().get("repository-context").audience())
                    .isEqualTo("repository-context-mcp");
            assertThat(properties.servers().get("repository-context").expectedVersion()).isEqualTo("0.1.0");
            assertThat(properties.servers().get("repository-context").allowedTools())
                    .containsExactlyInAnyOrder("context.get_repository_rules", "context.list_tree");
            assertThat(properties.servers().get("sandbox-execution").enabled()).isTrue();
            assertThat(properties.servers().get("evidence").uri())
                    .isEqualTo(URI.create("http://evidence-mcp:8095"));
            assertThat(properties.servers().get("evidence").allowedTools()).containsExactlyInAnyOrder(
                    "evidence.store", "evidence.create_manifest", "evidence.get_summary", "evidence.read");
        });
    }

    @Test
    void rejectsServerUrisContainingCredentials() {
        contextRunner.withPropertyValues(
                        "ai-factory.mcp.client.servers.repository-context.uri=http://user:secret@repository-context-mcp:8091")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsPerTaskConcurrencyAboveTheServerLimit() {
        contextRunner.withPropertyValues(
                        "ai-factory.mcp.client.max-inflight-per-server=2",
                        "ai-factory.mcp.client.max-inflight-per-task=3")
                .run(context -> assertThat(context).hasFailed());
    }

    private static String[] validProperties() {
        return new String[]{
                "ai-factory.mcp.client.enabled=true",
                "ai-factory.mcp.client.request-timeout=20s",
                "ai-factory.mcp.client.max-response-bytes=65536",
                "ai-factory.mcp.client.max-inflight-per-server=16",
                "ai-factory.mcp.client.max-inflight-per-task=4",
                "ai-factory.mcp.client.accepted-protocol-versions=2025-11-25,2025-06-18",
                "ai-factory.mcp.client.retry.read-only.max-attempts=3",
                "ai-factory.mcp.client.retry.read-only.initial-backoff=200ms",
                "ai-factory.mcp.client.retry.read-only.max-backoff=2s",
                "ai-factory.mcp.client.retry.read-only.multiplier=2.0",
                "ai-factory.mcp.client.retry.read-only.jitter=0.2",
                "ai-factory.mcp.client.retry.effectful.max-attempts=2",
                "ai-factory.mcp.client.retry.effectful.initial-backoff=500ms",
                "ai-factory.mcp.client.retry.effectful.max-backoff=2s",
                "ai-factory.mcp.client.retry.effectful.multiplier=2.0",
                "ai-factory.mcp.client.retry.effectful.jitter=0.2",
                "ai-factory.mcp.client.servers.repository-context.enabled=false",
                "ai-factory.mcp.client.servers.repository-context.uri=http://repository-context-mcp:8091",
                "ai-factory.mcp.client.servers.repository-context.audience=repository-context-mcp",
                "ai-factory.mcp.client.servers.repository-context.request-timeout=20s",
                "ai-factory.mcp.client.servers.repository-context.expected-name=repository-context-mcp",
                "ai-factory.mcp.client.servers.repository-context.expected-version=0.1.0",
                "ai-factory.mcp.client.servers.repository-context.allowed-tools=context.get_repository_rules,context.list_tree",
                "ai-factory.mcp.client.servers.sandbox-execution.enabled=true",
                "ai-factory.mcp.client.servers.sandbox-execution.uri=http://sandbox-execution-mcp:8092",
                "ai-factory.mcp.client.servers.sandbox-execution.audience=sandbox-execution-mcp",
                "ai-factory.mcp.client.servers.sandbox-execution.request-timeout=20s",
                "ai-factory.mcp.client.servers.sandbox-execution.expected-name=sandbox-execution-mcp",
                "ai-factory.mcp.client.servers.sandbox-execution.expected-version=0.1.0",
                "ai-factory.mcp.client.servers.sandbox-execution.allowed-tools=sandbox.run_tests,sandbox.get_execution",
                "ai-factory.mcp.client.servers.evidence.enabled=true",
                "ai-factory.mcp.client.servers.evidence.uri=http://evidence-mcp:8095",
                "ai-factory.mcp.client.servers.evidence.audience=evidence-mcp",
                "ai-factory.mcp.client.servers.evidence.request-timeout=20s",
                "ai-factory.mcp.client.servers.evidence.expected-name=evidence-mcp",
                "ai-factory.mcp.client.servers.evidence.expected-version=0.1.0",
                "ai-factory.mcp.client.servers.evidence.allowed-tools=evidence.store,evidence.create_manifest,evidence.get_summary,evidence.read"
        };
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(McpClientProperties.class)
    static class PropertiesConfiguration {
    }
}
