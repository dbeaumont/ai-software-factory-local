package com.example.aifactory.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessRunnerTest {
    @Test
    void redactsSonarTokenFromCommandErrors() {
        String command = ProcessRunner.commandForError(List.of("docker", "run", "SONAR_TOKEN=secret-value"));

        assertTrue(command.contains("SONAR_TOKEN=[REDACTED]"));
        assertFalse(command.contains("secret-value"));
    }
}
