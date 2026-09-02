package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@ConfigurationProperties(prefix = "ai-factory.mcp.client")
public record McpClientProperties(
        boolean enabled,
        Duration requestTimeout,
        int maxResponseBytes,
        int maxInflightGlobal,
        int maxInflightPerServer,
        int maxInflightPerTask,
        int maxInflightPerRole,
        Set<String> acceptedProtocolVersions,
        Retry retry,
        Map<String, Server> servers) {

    private static final Pattern SERVER_NAME = Pattern.compile("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$");
    private static final Pattern SERVER_VERSION = Pattern.compile("^[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$");
    private static final Pattern TOOL_NAME = Pattern.compile("^[a-z][a-z0-9]*(?:[._][a-z][a-z0-9_]*)+$");

    @ConstructorBinding
    public McpClientProperties {
        requireDuration("requestTimeout", requestTimeout);
        requireRange("maxResponseBytes", maxResponseBytes, 1, 1_048_576);
        requireRange("maxInflightGlobal", maxInflightGlobal, 1, 256);
        requireRange("maxInflightPerServer", maxInflightPerServer, 1, Math.min(64, maxInflightGlobal));
        requireRange("maxInflightPerTask", maxInflightPerTask, 1, maxInflightPerServer);
        requireRange("maxInflightPerRole", maxInflightPerRole, 1, maxInflightGlobal);
        if (acceptedProtocolVersions == null || acceptedProtocolVersions.isEmpty()
                || acceptedProtocolVersions.stream().anyMatch(version -> version == null || version.isBlank())) {
            throw new IllegalArgumentException("at least one MCP protocol version must be accepted");
        }
        acceptedProtocolVersions = Set.copyOf(acceptedProtocolVersions);
        Objects.requireNonNull(retry, "retry is required");
        if (servers == null || servers.isEmpty()) {
            throw new IllegalArgumentException("at least one MCP server must be configured");
        }
        servers.forEach((name, server) -> {
            if (name == null || !SERVER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("invalid MCP server key: " + name);
            }
            Objects.requireNonNull(server, "MCP server configuration is required for " + name);
        });
        servers = Map.copyOf(servers);
    }

    /** Compatibility constructor retained for focused tests and legacy embeddings. */
    public McpClientProperties(boolean enabled, Duration requestTimeout, int maxResponseBytes,
                               int maxInflightPerServer, int maxInflightPerTask,
                               Set<String> acceptedProtocolVersions, Retry retry, Map<String, Server> servers) {
        this(enabled, requestTimeout, maxResponseBytes, Math.max(maxInflightPerServer, 32),
                maxInflightPerServer, maxInflightPerTask, Math.max(maxInflightPerTask, 8),
                acceptedProtocolVersions, retry, servers);
    }

    public record Server(
            boolean enabled,
            URI uri,
            String audience,
            Duration requestTimeout,
            String expectedName,
            String expectedVersion,
            Set<String> allowedTools) {
        public Server {
            Objects.requireNonNull(uri, "server uri is required");
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                throw new IllegalArgumentException("server uri must use http or https");
            }
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("server uri must be an absolute authority without credentials, query or fragment");
            }
            if (audience == null || audience.isBlank() || audience.length() > 255) {
                throw new IllegalArgumentException("server audience is required and must not exceed 255 characters");
            }
            requireDuration("server requestTimeout", requestTimeout);
            if (expectedName == null || !SERVER_NAME.matcher(expectedName).matches()) {
                throw new IllegalArgumentException("server expectedName must use kebab-case");
            }
            if (expectedVersion == null || !SERVER_VERSION.matcher(expectedVersion).matches()) {
                throw new IllegalArgumentException("server expectedVersion must be a semantic version");
            }
            if (allowedTools == null || allowedTools.isEmpty()
                    || allowedTools.stream().anyMatch(tool -> tool == null || !TOOL_NAME.matcher(tool).matches())) {
                throw new IllegalArgumentException("server allowedTools must contain valid tool names");
            }
            allowedTools = Set.copyOf(allowedTools);
        }
    }

    public record Retry(RetryPolicy readOnly, RetryPolicy effectful) {
        public Retry {
            Objects.requireNonNull(readOnly, "readOnly retry policy is required");
            Objects.requireNonNull(effectful, "effectful retry policy is required");
            if (effectful.maxAttempts() > 2) {
                throw new IllegalArgumentException("effectful retry maxAttempts must not exceed 2");
            }
        }
    }

    public record RetryPolicy(
            int maxAttempts,
            Duration initialBackoff,
            Duration maxBackoff,
            double multiplier,
            double jitter) {
        public RetryPolicy {
            requireRange("retry maxAttempts", maxAttempts, 1, 5);
            requireDuration("retry initialBackoff", initialBackoff);
            requireDuration("retry maxBackoff", maxBackoff);
            if (maxBackoff.compareTo(initialBackoff) < 0) {
                throw new IllegalArgumentException("retry maxBackoff must be greater than or equal to initialBackoff");
            }
            if (multiplier < 1.0 || multiplier > 10.0) {
                throw new IllegalArgumentException("retry multiplier must be between 1 and 10");
            }
            if (jitter < 0.0 || jitter > 1.0) {
                throw new IllegalArgumentException("retry jitter must be between 0 and 1");
            }
        }
    }

    private static void requireDuration(String name, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
