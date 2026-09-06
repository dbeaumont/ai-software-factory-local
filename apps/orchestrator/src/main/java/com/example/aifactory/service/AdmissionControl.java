package com.example.aifactory.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/** Read-only application view of the durable operator-controlled admission switch. */
@Component
public final class AdmissionControl {
    private final JdbcTemplate jdbc;

    public AdmissionControl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Status status() {
        try {
            List<Status> states = jdbc.query("SELECT admissions_open, reason, revision, updated_at "
                            + "FROM factory_admission_control WHERE control_key = 'global'",
                    (row, index) -> new Status(row.getBoolean("admissions_open"), row.getString("reason"),
                            row.getLong("revision"), row.getTimestamp("updated_at").toInstant()));
            if (states.size() != 1) return Status.unavailable();
            return states.getFirst();
        } catch (RuntimeException unavailable) {
            return Status.unavailable();
        }
    }

    public record Status(boolean admissionsOpen, String reason, long revision, Instant updatedAt) {
        private static Status unavailable() {
            return new Status(false, "admission_control_unavailable", 0, Instant.EPOCH);
        }
    }
}
