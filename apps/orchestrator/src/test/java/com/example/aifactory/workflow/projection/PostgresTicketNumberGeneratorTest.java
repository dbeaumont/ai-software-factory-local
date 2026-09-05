package com.example.aifactory.workflow.projection;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresTicketNumberGeneratorTest {
    @Test
    void allocatesMonotonicNumbersFromTheDatabaseSequence() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:ticket-sequence;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SEQUENCE task_ticket_number_seq START WITH 41");
        PostgresTicketNumberGenerator generator = new PostgresTicketNumberGenerator(jdbc);

        assertThat(generator.next()).isEqualTo("AF-0041");
        assertThat(generator.next()).isEqualTo("AF-0042");
    }
}
