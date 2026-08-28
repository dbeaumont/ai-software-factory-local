package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryContextServiceTest {
    @TempDir
    Path repository;

    @Test
    void excludesSensitiveFilesAndRedactsSensitiveSettings() throws Exception {
        Files.writeString(repository.resolve(".env"), "API_KEY=must-not-leak");
        Files.writeString(repository.resolve("application.properties"), "service.token=must-not-leak\nfeature.enabled=true");
        Files.writeString(repository.resolve("Application.java"), "class Application {}");

        String context = new RepositoryContextService().collect(repository);

        assertFalse(context.contains("must-not-leak"));
        assertTrue(context.contains("service.token=[REDACTED]"));
        assertTrue(context.contains("class Application {}"));
    }
}
