package com.example.aifactory.context.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.security.Principal;

/** Binds each MCP tool call's actor to its JWT role and tool-specific scope before SDK dispatch. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@ConditionalOnProperty(name = "ai-factory.mcp.role-security.enabled", havingValue = "true")
final class McpToolAuthorizationFilter implements WebFilter {
    private final ObjectMapper mapper;
    private final McpRoleSecurityProperties properties;

    McpToolAuthorizationFilter(ObjectMapper mapper, McpRoleSecurityProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (exchange.getRequest().getMethod() != HttpMethod.POST
                || !"/mcp".equals(exchange.getRequest().getPath().value())) return chain.filter(exchange);
        return exchange.getPrincipal().flatMap(principal -> DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(buffer -> authorizeAndContinue(exchange, chain, principal, buffer)))
                .switchIfEmpty(deny(exchange));
    }

    private Mono<Void> authorizeAndContinue(ServerWebExchange exchange, WebFilterChain chain,
                                            Principal principal, DataBuffer buffer) {
        byte[] body = new byte[buffer.readableByteCount()];
        buffer.read(body);
        DataBufferUtils.release(buffer);
        try {
            authorize(principal, mapper.readTree(body), properties.serverName());
        } catch (Exception failure) {
            java.util.Arrays.fill(body, (byte) 0);
            return deny(exchange);
        }
        ServerHttpRequestDecorator request = new ServerHttpRequestDecorator(exchange.getRequest()) {
            @Override public Flux<DataBuffer> getBody() {
                return Flux.just(exchange.getResponse().bufferFactory().wrap(body));
            }
        };
        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(ignored -> java.util.Arrays.fill(body, (byte) 0));
    }

    static void authorize(Principal principal, JsonNode request, String serverName) {
        if (!(principal instanceof JwtAuthenticationToken jwt)) throw new SecurityException("MCP JWT required");
        if (request == null || !request.isObject()) throw new SecurityException("MCP batch requests are forbidden");
        String role = jwt.getToken().getClaimAsString("role");
        String clientId = jwt.getToken().getClaimAsString("client_id");
        String expectedClient = "workflow".equals(role)
                ? "ai-factory-orchestrator" : "ai-factory-agent-" + role;
        if (role == null || !role.matches("[a-z][a-z0-9-]{1,63}")
                || !expectedClient.equals(clientId)
                || !hasScope(jwt, "mcp.role." + role)) {
            throw new SecurityException("MCP role identity is invalid");
        }
        String method = request.path("method").asText();
        if ("tools/call".equals(method)) {
            String tool = request.path("params").path("name").asText();
            String actor = request.path("params").path("arguments").path("actor").asText();
            if (!role.equals(actor) || !tool.matches("[a-z][a-z0-9_.-]{1,127}")
                    || !hasScope(jwt, "mcp.tool." + tool)) {
                throw new SecurityException("MCP tool grant is invalid");
            }
        } else if (!java.util.Set.of("initialize", "notifications/initialized", "tools/list", "ping")
                .contains(method) || !hasScope(jwt, "mcp.connect." + serverName)) {
            throw new SecurityException("MCP connection grant is invalid");
        }
    }

    private static boolean hasScope(JwtAuthenticationToken jwt, String scope) {
        return jwt.getAuthorities().stream().anyMatch(authority ->
                authority.getAuthority().equals("SCOPE_" + scope));
    }

    private static Mono<Void> deny(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }
}
