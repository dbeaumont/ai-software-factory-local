package com.example.aifactory.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionControlTest {
    @Test
    void readsTheSingleDurableOperatorState() {
        JdbcTemplate jdbc = jdbc("admission-control");
        jdbc.execute("CREATE TABLE factory_admission_control (control_key varchar(32), "
                + "admissions_open boolean, reason varchar(256), revision bigint, updated_at timestamp with time zone)");
        jdbc.update("INSERT INTO factory_admission_control VALUES ('global', false, 'temporal_cutover', 7, "
                + "TIMESTAMP WITH TIME ZONE '2026-09-06 00:00:00Z')");

        AdmissionControl.Status status = new AdmissionControl(jdbc).status();

        assertThat(status.admissionsOpen()).isFalse();
        assertThat(status.reason()).isEqualTo("temporal_cutover");
        assertThat(status.revision()).isEqualTo(7);
    }

    @Test
    void failsClosedWhenTheControlCannotBeRead() {
        AdmissionControl.Status status = new AdmissionControl(jdbc("admission-control-missing")).status();

        assertThat(status.admissionsOpen()).isFalse();
        assertThat(status.reason()).isEqualTo("admission_control_unavailable");
        assertThat(status.revision()).isZero();
    }

    private static JdbcTemplate jdbc(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return new JdbcTemplate(dataSource);
    }
}
