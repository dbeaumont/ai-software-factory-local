package com.example.aifactory.context.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolAuthorizationFilterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsOnlyRoleBoundClientActorAndToolScope() throws Exception {
        var call = mapper.readTree("""
                {"jsonrpc":"2.0","method":"tools/call","params":{
                  "name":"context.read_file","arguments":{"actor":"developer"}}}
                """);
        JwtAuthenticationToken valid = token("developer", "context.read_file");

        assertThatCode(() -> McpToolAuthorizationFilter.authorize(
                valid, call, "repository-context-mcp")).doesNotThrowAnyException();
        assertThatThrownBy(() -> McpToolAuthorizationFilter.authorize(
                token("developer", "context.list_tree"), call, "repository-context-mcp"))
                .isInstanceOf(SecurityException.class);
        ((tools.jackson.databind.node.ObjectNode) call.path("params").path("arguments"))
                .put("actor", "workflow");
        assertThatThrownBy(() -> McpToolAuthorizationFilter.authorize(
                valid, call, "repository-context-mcp")).isInstanceOf(SecurityException.class);
    }

    private static JwtAuthenticationToken token(String role, String tool) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("header.payload.signature").header("alg", "RS256")
                .subject("ai-factory-agent-" + role).issuedAt(now).expiresAt(now.plusSeconds(300))
                .claim("role", role).claim("client_id", "ai-factory-agent-" + role).build();
        return new JwtAuthenticationToken(jwt, List.of(
                new SimpleGrantedAuthority("SCOPE_mcp.role." + role),
                new SimpleGrantedAuthority("SCOPE_mcp.tool." + tool),
                new SimpleGrantedAuthority("SCOPE_mcp.connect.repository-context-mcp")));
    }
}
