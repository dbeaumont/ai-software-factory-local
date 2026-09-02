package com.example.aifactory.workflow.migration;

import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskStatus;
import com.example.aifactory.model.TaskView;
import com.example.aifactory.service.InMemoryTaskMemory;
import com.example.aifactory.workflow.EvidenceRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyTaskMigratorTest {
    @Test
    void archivesTerminalTasksWithoutMutatingOrRestartingThemAndLeavesActiveTasksOnLegacy() {
        InMemoryTaskMemory legacy = new InMemoryTaskMemory();
        TaskState completed = new TaskState("completed-1", "AF-0042", new TaskRequest(
                "https://example.test/team/api.git", "main", "add an endpoint", null));
        completed.sourceCommit = "a".repeat(40);
        completed.plan = "legacy plan";
        completed.patch = "legacy patch";
        completed.pullRequestUrl = "https://example.test/team/api/pulls/42";
        completed.status = TaskStatus.PR_CREATED;
        TaskState active = new TaskState("active-1", "AF-0043", new TaskRequest(
                "https://example.test/team/api.git", "main", "fix a test", null));
        legacy.save(completed);
        legacy.save(active);
        TaskView before = completed.view();
        InMemoryEvidence evidence = new InMemoryEvidence();
        RecordingTarget target = new RecordingTarget();
        ObjectMapper mapper = new ObjectMapper();

        LegacyTaskMigrator.MigrationResult result = new LegacyTaskMigrator(
                legacy, evidence, target, mapper).migrateTerminalTasks();

        assertThat(result.imported()).isOne();
        assertThat(result.skippedActive()).isOne();
        assertThat(result.importedTaskIds()).containsExactly("completed-1");
        assertThat(legacy.find("completed-1")).containsSame(completed);
        assertThat(legacy.find("active-1")).containsSame(active);
        assertThat(completed.view()).isEqualTo(before);
        LegacyTaskMigrationTarget.TaskRecord record = target.records.getFirst();
        assertThat(record.targetStatus()).isEqualTo("COMPLETED");
        assertThat(record.legacyStatus()).isEqualTo("PR_CREATED");
        assertThat(record.sourceCommitVerified()).isTrue();
        assertThat(record.sourceCommit()).isEqualTo(completed.sourceCommit);

        TaskView restored = new MigratedTaskReader(evidence, mapper).read(record);
        assertThat(restored.id()).isEqualTo(before.id());
        assertThat(restored.ticketNumber()).isEqualTo(before.ticketNumber());
        assertThat(restored.status()).isEqualTo(TaskStatus.PR_CREATED);
        assertThat(restored.plan()).isEqualTo(before.plan());
        assertThat(restored.patch()).isEqualTo(before.patch());
        assertThat(restored.pullRequestUrl()).isEqualTo(before.pullRequestUrl());
        assertThat(restored.steps()).isEqualTo(before.steps());
    }

    private static final class RecordingTarget implements LegacyTaskMigrationTarget {
        private List<TaskRecord> records = List.of();

        @Override public void importAtomically(List<TaskRecord> tasks) {
            records = List.copyOf(tasks);
        }
    }

    private static final class InMemoryEvidence implements EvidenceRepository {
        private final Map<String, Stored> stored = new LinkedHashMap<>();

        @Override public StoredEvidence store(StoreRequest request) {
            String uri = "evidence://" + request.taskId() + '/' + request.attemptId()
                    + "/legacy-task-snapshot/" + request.digest();
            stored.put(uri, new Stored(request.content(), request.digest()));
            Instant now = Instant.parse("2026-09-02T12:00:00Z");
            return new StoredEvidence(uri, request.digest(), "COMPLETE", request.mediaType(),
                    request.content().length, "CONFIDENTIAL", now.plusSeconds(31_536_000), now);
        }

        @Override public RawEvidence read(ReadRequest request) {
            Stored value = stored.get(request.uri());
            return new RawEvidence(request.uri(), "legacy-task-snapshot", value.digest,
                    "COMPLETE", "CONFIDENTIAL", value.content);
        }

        @Override public StoredManifest createManifest(ManifestRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override public EvidenceSummary getSummary(String taskId, String attemptId, String uri, String actor) {
            throw new UnsupportedOperationException();
        }

        private record Stored(byte[] content, String digest) {}
    }
}
