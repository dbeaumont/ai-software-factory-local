package com.example.aifactory.workflow.migration;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresLegacyTaskMigrationTargetTest {
    private JdbcTemplate jdbc;
    private PostgresLegacyTaskMigrationTarget target;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:legacy-import-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE tasks(task_id varchar PRIMARY KEY, ticket_number varchar UNIQUE, "
                + "repository_id varchar, current_attempt_id varchar, source_commit varchar, "
                + "requirement_digest varchar, status varchar, created_at timestamp with time zone, "
                + "updated_at timestamp with time zone, version bigint)");
        jdbc.execute("CREATE TABLE legacy_task_imports(task_id varchar PRIMARY KEY, attempt_id varchar, "
                + "source_commit varchar, source_commit_verified boolean, legacy_status varchar, "
                + "snapshot_uri varchar, snapshot_digest varchar, snapshot_classification varchar, "
                + "migrated_at timestamp with time zone)");
        target = new PostgresLegacyTaskMigrationTarget(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @Test
    void importsIdempotentlyWithoutCreatingTemporalMetadata() {
        LegacyTaskMigrationTarget.TaskRecord task = task("a".repeat(64));

        target.importAtomically(List.of(task));
        target.importAtomically(List.of(task));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM legacy_task_imports", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ticket_number FROM tasks", String.class)).isEqualTo("AF-0042");
    }

    @Test
    void rollsBackTheBatchWhenAReimportDiverges() {
        target.importAtomically(List.of(task("a".repeat(64))));

        assertThatThrownBy(() -> target.importAtomically(List.of(task("b".repeat(64)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Divergent");

        assertThat(jdbc.queryForObject("SELECT snapshot_digest FROM legacy_task_imports", String.class))
                .isEqualTo("a".repeat(64));
    }

    private static LegacyTaskMigrationTarget.TaskRecord task(String digest) {
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        return new LegacyTaskMigrationTarget.TaskRecord("legacy-1", "AF-0042", "legacy-repo",
                "legacy-legacy-1", "c".repeat(40), true, "d".repeat(64), "COMPLETED", "PR_CREATED",
                now, now.plusSeconds(10), "evidence://legacy-1/snapshot/" + digest, digest, "CONFIDENTIAL");
    }
}
