package com.example.aifactory.service;

import com.example.aifactory.config.McpClientProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class McpResponseValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsAResponseMatchingThePinnedLocalSchema() throws Exception {
        McpResponseValidator validator = new McpResponseValidator(mapper, properties(65_536));

        assertThatNoException().isThrownBy(() -> validator.validate("context.list_tree", mapper.readTree("""
                {
                  "source_commit": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "entries": [{"path": "src/App.java", "type": "file", "size": 42}],
                  "truncated": false
                }
                """)));
    }

    @Test
    void rejectsMissingAndUnexpectedFieldsWithoutEchoingTheirValues() throws Exception {
        McpResponseValidator validator = new McpResponseValidator(mapper, properties(65_536));

        assertThatThrownBy(() -> validator.validate("context.list_tree", mapper.readTree("""
                {"entries": [], "truncated": false, "injected_secret": "do-not-log"}
                """)))
                .isInstanceOf(McpResponseValidator.McpResponseValidationException.class)
                .hasMessageContaining("violates the local schema")
                .hasMessageNotContaining("do-not-log");
    }

    @Test
    void rejectsTheSerializedResponseAboveTheConfiguredByteLimit() throws Exception {
        McpResponseValidator validator = new McpResponseValidator(mapper, properties(128));

        assertThatThrownBy(() -> validator.validate("context.read_file", mapper.readTree("""
                {
                  "path": "src/App.java", "start_line": 1, "end_line": 1,
                  "content": "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
                  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "truncated": false
                }
                """)))
                .isInstanceOf(McpResponseValidator.McpResponseValidationException.class)
                .hasMessageContaining("byte limit");
    }

    private static McpClientProperties properties(int maxResponseBytes) {
        McpClientProperties.RetryPolicy readOnly = new McpClientProperties.RetryPolicy(
                3, Duration.ofMillis(200), Duration.ofSeconds(2), 2.0, 0.2);
        McpClientProperties.RetryPolicy effectful = new McpClientProperties.RetryPolicy(
                2, Duration.ofMillis(500), Duration.ofSeconds(2), 2.0, 0.2);
        McpClientProperties.Server server = new McpClientProperties.Server(
                true, URI.create("http://repository-context-mcp:8091"), "repository-context-mcp",
                Duration.ofSeconds(20), "repository-context-mcp", "0.1.0", Set.of("context.list_tree"));
        return new McpClientProperties(true, Duration.ofSeconds(20), maxResponseBytes, 16, 4,
                Set.of("2025-06-18"), new McpClientProperties.Retry(readOnly, effectful),
                Map.of("repository-context", server));
    }
}
