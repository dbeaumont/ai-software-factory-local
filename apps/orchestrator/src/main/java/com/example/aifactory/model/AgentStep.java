package com.example.aifactory.model;

import java.time.Instant;

public record AgentStep(String name, String status, String summary, Instant timestamp) {
}
