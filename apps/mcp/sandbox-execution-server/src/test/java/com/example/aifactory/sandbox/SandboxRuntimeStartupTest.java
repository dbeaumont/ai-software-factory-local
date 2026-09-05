package com.example.aifactory.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxRuntimeStartupTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void refusesUnknownRuntimeInsteadOfFallingBack() {
        RuntimeException failure = assertThrows(RuntimeException.class, () -> {
            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(
                    SandboxExecutionMcpApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(
                            "--ai-factory.sandbox.runtime=unknown",
                            "--ai-factory.sandbox.workspace-root=" + temporaryDirectory.resolve("tasks"),
                            "--ai-factory.sandbox.state-root=" + temporaryDirectory.resolve("state"),
                            "--spring.main.banner-mode=off",
                            "--logging.level.root=OFF")) {
                // The context must never start without an explicit SandboxRuntime bean.
            }
        });
        assertTrue(causeMentions(failure, "SandboxRuntime"),
                () -> "startup failed for an unrelated reason: " + failure);
    }

    private static boolean causeMentions(Throwable failure, String expected) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(expected)) {
                return true;
            }
        }
        return false;
    }
}
