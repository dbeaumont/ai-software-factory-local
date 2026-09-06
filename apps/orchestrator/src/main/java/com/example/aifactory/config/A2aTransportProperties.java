package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/** File-backed mTLS and card-trust settings for the orchestrator A2A client. */
@ConfigurationProperties("ai-factory.a2a.transport")
public record A2aTransportProperties(
        String certificate,
        String privateKey,
        String trustCertificate,
        String cardTrustFile,
        String cardIssuer,
        URI cardProviderUrl) {
}
