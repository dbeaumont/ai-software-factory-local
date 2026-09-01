package com.example.aifactory.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;

@Component
public final class McpTransportSecurityValidator {
    public McpTransportSecurityValidator(McpClientProperties properties, Environment environment) {
        boolean development = environment.matchesProfiles("local", "dev", "test");
        properties.servers().forEach((name, server) -> validate(name, server.uri(), development));
    }

    static void validate(String name, URI uri, boolean development) {
        if ("https".equalsIgnoreCase(uri.getScheme())) return;
        if ("http".equalsIgnoreCase(uri.getScheme()) && (development || isLoopback(uri.getHost()))) return;
        throw new IllegalStateException("MCP server " + name + " must use HTTPS outside local development");
    }

    private static boolean isLoopback(String host) {
        if ("localhost".equalsIgnoreCase(host)) return true;
        if (host == null || (!host.matches("[0-9.]+") && !host.contains(":"))) return false;
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }
}
