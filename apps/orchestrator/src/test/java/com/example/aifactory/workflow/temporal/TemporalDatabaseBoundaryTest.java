package com.example.aifactory.workflow.temporal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevents application code from coupling itself to Temporal's private persistence schema. */
class TemporalDatabaseBoundaryTest {
    private static final Path MAIN_SOURCES = Path.of("src/main");
    private static final List<String> INTERNAL_DATABASE_REFERENCES = List.of(
            "jdbc:postgresql://temporal-db",
            "jdbc:postgresql://temporal:",
            "temporal_visibility",
            "temporal_visibility_version",
            "schema_version_partition");

    @Test
    void applicationSourcesNeverReferenceTemporalDatabaseInternals() throws IOException {
        try (var paths = Files.walk(MAIN_SOURCES)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String content = Files.readString(path);
                INTERNAL_DATABASE_REFERENCES.forEach(reference -> assertThat(content)
                        .as("%s must not access Temporal persistence through %s", path, reference)
                        .doesNotContain(reference));
            }
        }
    }

    @Test
    void temporalProjectionReadsHistoryThroughTheSdk() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/aifactory/workflow/projection/TemporalProjectionHistorySource.java"));

        assertThat(source).contains("io.temporal.client.WorkflowClient");
        assertThat(source).doesNotContain("java.sql.", "JdbcTemplate", "DataSource");
    }
}
