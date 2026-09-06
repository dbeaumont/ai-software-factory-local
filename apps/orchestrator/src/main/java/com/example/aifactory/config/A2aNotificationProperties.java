package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai-factory.a2a.notifications")
public record A2aNotificationProperties(boolean enabled, String hmacSecretFile, int maxBodyBytes) {
    public A2aNotificationProperties {
        if (maxBodyBytes < 1 || maxBodyBytes > 1_048_576) {
            throw new IllegalArgumentException("A2A notification body limit is invalid");
        }
        if (enabled && (hmacSecretFile == null || hmacSecretFile.isBlank())) {
            throw new IllegalArgumentException("A2A notification HMAC secret file is required");
        }
    }
}
