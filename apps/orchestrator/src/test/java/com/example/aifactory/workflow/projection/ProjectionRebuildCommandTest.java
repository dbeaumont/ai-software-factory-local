package com.example.aifactory.workflow.projection;

import com.example.aifactory.service.SecurityAuditJournal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectionRebuildCommandTest {
    @Test
    void previewsABoundedPageAndReturnsAResumeCursor() {
        ProjectionRebuildCatalog catalog = mock(ProjectionRebuildCatalog.class);
        ProjectionRebuilder rebuilder = mock(ProjectionRebuilder.class);
        SecurityAuditJournal audit = mock(SecurityAuditJournal.class);
        when(catalog.pageAfter("task-0", 2)).thenReturn(List.of(
                new ProjectionRebuildCatalog.Candidate("task-1", "workflow-1", "run-1"),
                new ProjectionRebuildCatalog.Candidate("task-2", "workflow-2", "run-2")));
        ProjectionRebuildCommand command = new ProjectionRebuildCommand(
                catalog, rebuilder, audit, new SimpleMeterRegistry());

        ProjectionRebuildCommand.Result result = command.execute(
                new ProjectionRebuildCommand.Request(true, 2, "task-0", "operator"));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.complete()).isFalse();
        assertThat(result.nextTaskId()).isEqualTo("task-2");
        verify(rebuilder).preview("workflow-1", "run-1");
        verify(rebuilder).preview("workflow-2", "run-2");
        verify(audit).append(SecurityAuditJournal.EventType.COMMAND_INTENT,
                "projection-rebuild", "operator", "dry-run/task-0/2", "REQUESTED");
    }

    @Test
    void isolatesFailuresAndKeepsProcessingThePage() {
        ProjectionRebuildCatalog catalog = (cursor, limit) -> List.of(
                new ProjectionRebuildCatalog.Candidate("task-1", "workflow-1", "run-1"),
                new ProjectionRebuildCatalog.Candidate("task-2", "workflow-2", "run-2"));
        ProjectionRebuilder rebuilder = mock(ProjectionRebuilder.class);
        doThrow(new SecurityException("digest mismatch")).when(rebuilder).rebuild("workflow-1", "run-1");
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ProjectionRebuildCommand command = new ProjectionRebuildCommand(
                catalog, rebuilder, mock(SecurityAuditJournal.class), metrics);

        ProjectionRebuildCommand.Result result = command.execute(
                new ProjectionRebuildCommand.Request(false, 10, null, "operator"));

        assertThat(result.complete()).isTrue();
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failures()).containsExactly(
                new ProjectionRebuildCommand.Failure("task-1", "SecurityException"));
        verify(rebuilder).rebuild("workflow-2", "run-2");
        assertThat(metrics.get("ai_factory_projection_rebuild_total").tag("outcome", "success")
                .counter().count()).isEqualTo(1);
        assertThat(metrics.get("ai_factory_projection_rebuild_total").tag("outcome", "failure")
                .counter().count()).isEqualTo(1);
    }
}
