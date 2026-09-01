package com.example.aifactory.context;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_EVENT_STREAM;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "ai-factory.context.symbols.enabled=false")
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class RepositoryContextMcpFeatureFlagIntegrationTest {
    @Autowired
    ApplicationContext applicationContext;

    @Test
    void doesNotAdvertiseGetSymbolsWhenFeatureFlagIsDisabled() {
        WebTestClient.bindToApplicationContext(applicationContext).build()
                .post().uri("/mcp")
                .header("MCP-Protocol-Version", "2025-06-18")
                .contentType(APPLICATION_JSON)
                .accept(APPLICATION_JSON, TEXT_EVENT_STREAM)
                .bodyValue(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list", "params", Map.of()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.result.tools.length()").isEqualTo(5)
                .jsonPath("$.result.tools[?(@.name == 'context.get_symbols')]").doesNotExist();
    }
}
