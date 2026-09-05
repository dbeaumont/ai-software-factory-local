package com.example.aifactory.sandbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

@ConfigurationProperties(prefix = "ai-factory.sandbox.gke")
public record GkeControllerProperties(
        URI apiServer,
        String namespace,
        String controllerServiceAccount,
        String jobServiceAccount,
        String runtimeClassName,
        String workspaceClaim,
        String environmentSecret,
        Path tokenFile,
        Path caCertificateFile,
        Duration pollInterval,
        int ttlSecondsAfterFinished) {

    public GkeControllerProperties {
        if (apiServer == null || namespace == null || namespace.isBlank()
                || controllerServiceAccount == null || controllerServiceAccount.isBlank()
                || jobServiceAccount == null || jobServiceAccount.isBlank()
                || runtimeClassName == null || runtimeClassName.isBlank()
                || workspaceClaim == null || workspaceClaim.isBlank()
                || environmentSecret == null || environmentSecret.isBlank()
                || tokenFile == null || caCertificateFile == null
                || pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                || ttlSecondsAfterFinished < 0) {
            throw new IllegalArgumentException("invalid GKE sandbox controller configuration");
        }
    }
}
