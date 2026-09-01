package com.example.aifactory.sandbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@ConfigurationProperties(prefix = "ai-factory.sandbox-dependencies")
public record SandboxDependencyProperties(
        String npmRegistryUrl,
        String egressProxyUrl,
        String noProxy,
        String qualityNetwork) {

    public SandboxDependencyProperties {
        requireHttpUrl("npm registry", npmRegistryUrl);
        requireHttpUrl("egress proxy", egressProxyUrl);
        if (URI.create(egressProxyUrl).getPort() != 3128) {
            throw new IllegalArgumentException("sandbox egress proxy must use the controlled port 3128");
        }
        if (noProxy == null || noProxy.isBlank() || containsControlCharacter(noProxy)) {
            throw new IllegalArgumentException("sandbox no-proxy allow-list is required");
        }
        if (qualityNetwork == null || !qualityNetwork.matches("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$")) {
            throw new IllegalArgumentException("sandbox quality network name is invalid");
        }
    }

    public String gradleProxyOptions() {
        URI proxy = URI.create(egressProxyUrl);
        return "-Dhttp.proxyHost=" + proxy.getHost() + " -Dhttp.proxyPort=" + proxyPort()
                + " -Dhttps.proxyHost=" + proxy.getHost() + " -Dhttps.proxyPort=" + proxyPort()
                + " -Dhttp.nonProxyHosts=" + noProxy.replace(",", "|");
    }

    public String proxyHost() {
        return URI.create(egressProxyUrl).getHost();
    }

    private String proxyPort() {
        URI proxy = URI.create(egressProxyUrl);
        return Integer.toString(proxy.getPort() == -1 ? ("https".equals(proxy.getScheme()) ? 443 : 80)
                : proxy.getPort());
    }

    public String mavenNoProxyHosts() {
        return noProxy.replace(",", "|");
    }

    private static void requireHttpUrl(String name, String value) {
        try {
            URI uri = URI.create(value == null ? "" : value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || containsControlCharacter(value)) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("sandbox " + name + " must be an absolute HTTP(S) URL", exception);
        }
    }

    private static boolean containsControlCharacter(String value) {
        return value != null && value.chars().anyMatch(character -> character < 32 || character == 127);
    }
}
