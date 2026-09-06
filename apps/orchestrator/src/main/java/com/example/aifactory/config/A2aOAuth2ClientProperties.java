package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/** Client-credentials settings; the secret itself is read from a mounted file only inside an activity. */
@ConfigurationProperties("ai-factory.a2a.oauth2")
public record A2aOAuth2ClientProperties(
        boolean enabled,
        URI tokenUrl,
        String clientId,
        Path clientSecretFile,
        String audience,
        Duration maximumTokenLifetime,
        Duration requestTimeout) {

    public A2aOAuth2ClientProperties {
        maximumTokenLifetime = maximumTokenLifetime == null ? Duration.ofMinutes(5) : maximumTokenLifetime;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
        if (maximumTokenLifetime.isZero() || maximumTokenLifetime.isNegative()
                || maximumTokenLifetime.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("A2A OAuth2 tokens must be short-lived (at most 10 minutes)");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("A2A OAuth2 request timeout must be positive");
        }
        if (enabled && (tokenUrl == null || !"https".equalsIgnoreCase(tokenUrl.getScheme())
                || clientId == null || clientId.isBlank() || clientSecretFile == null
                || audience == null || audience.isBlank())) {
            throw new IllegalArgumentException("Enabled A2A OAuth2 client-credentials configuration is incomplete");
        }
    }
}
