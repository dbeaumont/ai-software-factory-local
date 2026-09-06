package com.example.aifactory.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/** Locks the release configuration to the post-cutover, A2A-only topology. */
class A2aCutoverConfigurationTest {
    @Test
    void applicationConfigurationHasNoA2aTransportSelector() {
        String relative = "src/main/resources/application.yml";
        assertThat(read(Path.of(relative))).as(relative)
                .doesNotContain("AI_FACTORY_A2A_ENABLED", "a2a.fleet.enabled", "A2A_DISABLED");
    }

    @Test
    void productionGraphCannotOmitA2aClientOrActivities() {
        assertThat(read(Path.of(
                "src/main/java/com/example/aifactory/config/A2aClientRuntimeConfiguration.java")))
                .doesNotContain("ConditionalOnProperty");
        assertThat(read(Path.of(
                "src/main/java/com/example/aifactory/workflow/temporal/TemporalActivityAdapters.java")))
                .contains("new Object[]{a2a}")
                .doesNotContain("ObjectProvider", "getIfAvailable");
        assertThat(read(Path.of(
                "src/main/java/com/example/aifactory/service/CutoverTicketAdmissionGate.java")))
                .contains("requireA2aFleet().then")
                .doesNotContain("a2a.enabled", "A2A_DISABLED");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot inspect " + path, exception);
        }
    }
}
