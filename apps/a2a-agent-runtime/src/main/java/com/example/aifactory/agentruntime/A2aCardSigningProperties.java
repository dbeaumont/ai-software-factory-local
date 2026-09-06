package com.example.aifactory.agentruntime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Card signing key set; private material is supplied as a mounted JWK Set in secured environments. */
@ConfigurationProperties("ai-factory.agent-runtime.card-signing")
public record A2aCardSigningProperties(String jwkSetPath, String activeKeyId) {
}
