package com.example.aifactory.evidence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;

@ConfigurationProperties(prefix = "ai-factory.evidence")
public record EvidenceProperties(Path stateRoot, int maxArtifactBytes) {
    public EvidenceProperties {
        if (stateRoot == null || !stateRoot.isAbsolute() || maxArtifactBytes < 1 || maxArtifactBytes > 16_777_216) {
            throw new IllegalArgumentException("invalid evidence storage configuration");
        }
    }
}
