package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.RoleScopedAgentContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpRoleTokenProviderTest {
    @TempDir Path temporary;

    @Test
    void loadsOnlyTheTokenWhoseClientIdentityMatchesTheActiveRole() throws Exception {
        Path tokenFile = temporary.resolve("token");
        Files.writeString(tokenFile, "role-bound-access-token-value\n");
        Files.setPosixFilePermissions(tokenFile,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        RoleScopedAgentContext role = RoleScopedAgentContext.load("developer", new ObjectMapper());

        AgentMcpProperties correct = properties("ai-factory-agent-developer", tokenFile);
        assertThat(new McpRoleTokenProvider(role, correct).acquire())
                .containsExactly("role-bound-access-token-value".toCharArray());
        Files.writeString(tokenFile, "rotated-role-bound-access-token\n");
        Files.setPosixFilePermissions(tokenFile,
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThat(new McpRoleTokenProvider(role, correct).acquire())
                .containsExactly("rotated-role-bound-access-token".toCharArray());
        assertThatThrownBy(() -> new McpRoleTokenProvider(
                role, properties("ai-factory-agent-test-design", tokenFile)).acquire())
                .isInstanceOf(SecurityException.class).hasMessageContaining("active agent role");
    }

    private static AgentMcpProperties properties(String clientId, Path tokenFile) {
        return new AgentMcpProperties(true, Duration.ofSeconds(20),
                URI.create("https://repository-context-mcp:8091"), URI.create("https://evidence-mcp:8095"),
                true, clientId, tokenFile);
    }
}
