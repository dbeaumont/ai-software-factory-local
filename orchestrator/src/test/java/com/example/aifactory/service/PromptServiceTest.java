package com.example.aifactory.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsPromptFromConfiguredRoot() throws Exception {
        Files.writeString(tempDir.resolve("planner.md"), "planner prompt");

        PromptService service = new PromptService(tempDir);

        assertEquals("planner prompt", service.load("planner"));
    }

    @Test
    void reportsConfiguredPromptLocation() {
        Path missingRoot = tempDir.resolve("missing");
        PromptService service = new PromptService(missingRoot);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> service.load("planner"));

        assertTrue(error.getMessage().contains("planner"));
        assertTrue(error.getMessage().contains("missing"));
    }
}
