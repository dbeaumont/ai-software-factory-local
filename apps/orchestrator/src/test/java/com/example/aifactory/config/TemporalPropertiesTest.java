package com.example.aifactory.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(valid());

    @Test
    void bindsNamespaceRetentionQueuesAndLocalSecurity() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            TemporalProperties properties = context.getBean(TemporalProperties.class);
            assertThat(properties.namespace()).isEqualTo("ai-factory-local");
            assertThat(properties.namespaceRetention()).isEqualTo(Duration.ofDays(7));
            assertThat(properties.taskQueues()).containsKeys(
                    "workflow", "context", "llm", "sandbox", "assurance", "evidence", "scm");
            assertThat(properties.security().tlsEnabled()).isFalse();
        });
    }

    @Test
    void rejectsCredentialsWithoutTls() {
        runner.withPropertyValues("ai-factory.temporal.security.api-key-file=/run/secrets/temporal")
                .run(context -> assertThat(context).hasFailed());
    }

    private static String[] valid() {
        return new String[]{"ai-factory.temporal.enabled=false", "ai-factory.temporal.target=temporal:7233",
                "ai-factory.temporal.namespace=ai-factory-local", "ai-factory.temporal.namespace-retention=P7D",
                "ai-factory.temporal.task-queues.workflow=ai-factory-workflows",
                "ai-factory.temporal.task-queues.context=ai-factory-context",
                "ai-factory.temporal.task-queues.llm=ai-factory-llm",
                "ai-factory.temporal.task-queues.sandbox=ai-factory-sandbox",
                "ai-factory.temporal.task-queues.assurance=ai-factory-assurance",
                "ai-factory.temporal.task-queues.evidence=ai-factory-evidence",
                "ai-factory.temporal.task-queues.scm=ai-factory-scm",
                "ai-factory.temporal.security.tls-enabled=false"};
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TemporalProperties.class)
    static class PropertiesConfiguration {}
}
