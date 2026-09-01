package com.example.aifactory.evidence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import java.nio.file.Path;

@ConfigurationProperties(prefix = "ai-factory.evidence")
public record EvidenceProperties(Path stateRoot, int maxArtifactBytes, String encryptionKey) {
    @ConstructorBinding
    public EvidenceProperties {
        if (stateRoot == null || !stateRoot.isAbsolute() || maxArtifactBytes < 1 || maxArtifactBytes > 16_777_216
                || encryptionKey == null || encryptionKey.length() < 32) {
            throw new IllegalArgumentException("invalid evidence storage configuration");
        }
    }

    public EvidenceProperties(Path stateRoot, int maxArtifactBytes) {
        this(stateRoot, maxArtifactBytes, "evidence-test-key-at-least-thirty-two-bytes");
    }
}
