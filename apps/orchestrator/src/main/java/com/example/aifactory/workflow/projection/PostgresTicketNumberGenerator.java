package com.example.aifactory.workflow.projection;

import com.example.aifactory.service.TicketNumberGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class PostgresTicketNumberGenerator implements TicketNumberGenerator {
    private final JdbcTemplate jdbc;

    public PostgresTicketNumberGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String next() {
        Long value = jdbc.queryForObject("SELECT nextval('task_ticket_number_seq')", Long.class);
        if (value == null || value < 1) throw new IllegalStateException("Task ticket sequence returned no value");
        return "AF-%04d".formatted(value);
    }
}
