package com.example.aifactory.workflow.projection;

import com.example.aifactory.service.SecurityAuditJournal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** Bounded and restartable operator command for rebuilding every known projection page by page. */
@Service
public final class ProjectionRebuildCommand {
    private final ProjectionRebuildCatalog catalog;
    private final ProjectionRebuilder rebuilder;
    private final SecurityAuditJournal audit;
    private final Counter rebuilt;
    private final Counter failed;

    public ProjectionRebuildCommand(ProjectionRebuildCatalog catalog, ProjectionRebuilder rebuilder,
                                    SecurityAuditJournal audit, MeterRegistry metrics) {
        this.catalog = catalog;
        this.rebuilder = rebuilder;
        this.audit = audit;
        this.rebuilt = Counter.builder("ai_factory_projection_rebuild_total")
                .tag("outcome", "success").register(metrics);
        this.failed = Counter.builder("ai_factory_projection_rebuild_total")
                .tag("outcome", "failure").register(metrics);
    }

    public Result execute(Request request) {
        Request command = validated(request);
        String cursor = command.afterTaskId() == null ? "" : command.afterTaskId();
        String reference = (command.dryRun() ? "dry-run/" : "apply/")
                + (cursor.isEmpty() ? "begin" : cursor) + '/' + command.batchSize();
        audit.append(SecurityAuditJournal.EventType.COMMAND_INTENT, "projection-rebuild", command.actor(),
                reference, "REQUESTED");
        List<Failure> failures = new ArrayList<>();
        List<ProjectionRebuildCatalog.Candidate> candidates = catalog.pageAfter(cursor, command.batchSize());
        for (ProjectionRebuildCatalog.Candidate candidate : candidates) {
            try {
                if (command.dryRun()) rebuilder.preview(candidate.workflowId(), candidate.runId());
                else rebuilder.rebuild(candidate.workflowId(), candidate.runId());
                rebuilt.increment();
            } catch (RuntimeException failure) {
                failed.increment();
                failures.add(new Failure(candidate.taskId(), failure.getClass().getSimpleName()));
            }
        }
        String next = candidates.isEmpty() ? null : candidates.getLast().taskId();
        boolean complete = candidates.size() < command.batchSize();
        String decision = failures.isEmpty() ? "COMPLETED" : "COMPLETED_WITH_FAILURES";
        audit.append(SecurityAuditJournal.EventType.COMMAND_ACCEPTED, "projection-rebuild", command.actor(),
                reference, decision);
        return new Result(command.dryRun(), candidates.size(), candidates.size() - failures.size(),
                List.copyOf(failures), complete, complete ? null : next);
    }

    private static Request validated(Request request) {
        if (request == null || request.batchSize() == null || request.batchSize() < 1 || request.batchSize() > 100
                || request.actor() == null || request.actor().isBlank() || request.actor().length() > 256
                || request.afterTaskId() != null && !request.afterTaskId().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Projection rebuild command is invalid");
        }
        return request;
    }

    public record Request(boolean dryRun, Integer batchSize, String afterTaskId, String actor) {}
    public record Failure(String taskId, String errorCode) {}
    public record Result(boolean dryRun, int scanned, int succeeded, List<Failure> failures,
                         boolean complete, String nextTaskId) {}
}
