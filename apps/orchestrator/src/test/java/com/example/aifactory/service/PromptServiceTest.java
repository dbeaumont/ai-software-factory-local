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

    @Test
    void fingerprintsPromptContentWithSha256() throws Exception {
        Files.writeString(tempDir.resolve("planner.md"), "planner prompt");

        String fingerprint = new PromptService(tempDir).fingerprint("planner");

        assertEquals("69cc103c237202f84d942f4a37d00fea422474aae1a6ef4e04a851874d72d7fe", fingerprint);
    }
}
