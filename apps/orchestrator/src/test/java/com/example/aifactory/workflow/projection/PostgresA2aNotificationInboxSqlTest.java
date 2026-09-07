package com.example.aifactory.workflow.projection;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresA2aNotificationInboxSqlTest {

    @Test
    void castsSerializedNotificationPayloadToJsonb() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/example/aifactory/workflow/projection/PostgresA2aNotificationInbox.java"));

        assertThat(source).contains("CAST(? AS jsonb)");
    }
}
