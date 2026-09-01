package com.example.aifactory.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringMcpToolInvokerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean("mcpSyncClients", List.class, List::of)
            .withBean(ObjectMapper.class)
            .withBean(SpringMcpToolInvoker.class);

    @Test
    void injectsTheNamedAutoConfiguredClientList() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            SpringMcpToolInvoker invoker = context.getBean(SpringMcpToolInvoker.class);
            McpToolInvoker.Availability availability = invoker.availability("sandbox-execution-mcp");
            assertThat(availability.available()).isFalse();
            assertThat(availability.error()).contains("sandbox-execution-mcp");
        });
    }
}
