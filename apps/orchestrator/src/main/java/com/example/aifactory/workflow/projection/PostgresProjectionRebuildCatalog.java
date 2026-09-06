package com.example.aifactory.workflow.projection;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PostgresProjectionRebuildCatalog implements ProjectionRebuildCatalog {
    private final JdbcTemplate jdbc;

    public PostgresProjectionRebuildCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Candidate> pageAfter(String taskId, int limit) {
        if (taskId == null || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Projection rebuild page is invalid");
        }
        return jdbc.query("SELECT wr.task_id, wr.workflow_id, CAST(wr.temporal_run_id AS varchar) "
                        + "FROM workflow_runs wr JOIN tasks t ON t.task_id = wr.task_id "
                        + "AND t.current_attempt_id = wr.attempt_id WHERE wr.task_id > ? "
                        + "ORDER BY wr.task_id LIMIT ?",
                (row, index) -> new Candidate(row.getString(1), row.getString(2), row.getString(3)),
                taskId, limit);
    }
}
