package com.example.aifactory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai-factory.mcp.kill-switch")
public record KillSwitchProperties(String controlFile) {
}
