package com.example.aifactory.workflow.migration;

import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskStatus;
import com.example.aifactory.workflow.EvidenceRepository;
import com.example.aifactory.workflow.TaskMemory;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Imports terminal in-memory tasks without mutating or re-running their legacy instances. */
public final class LegacyTaskMigrator {
    private static final Set<TaskStatus> TERMINAL = Set.of(TaskStatus.PR_CREATED, TaskStatus.CANCELLED, TaskStatus.FAILED);
    private static final String UNKNOWN_COMMIT = "0".repeat(40);

    private final TaskMemory legacyMemory;
    private final EvidenceRepository evidenceRepository;
    private final LegacyTaskMigrationTarget target;
    private final ObjectMapper objectMapper;

    public LegacyTaskMigrator(TaskMemory legacyMemory, EvidenceRepository evidenceRepository,
                              LegacyTaskMigrationTarget target, ObjectMapper objectMapper) {
        this.legacyMemory = legacyMemory;
        this.evidenceRepository = evidenceRepository;
        this.target = target;
        this.objectMapper = objectMapper;
    }

    public MigrationResult migrateTerminalTasks() {
        List<TaskState> legacyTasks = List.copyOf(legacyMemory.list());
        List<LegacyTaskMigrationTarget.TaskRecord> records = new ArrayList<>();
        int skippedActive = 0;
        for (TaskState state : legacyTasks) {
            if (!TERMINAL.contains(state.status)) {
                skippedActive++;
                continue;
            }
            records.add(archive(state));
        }
        target.importAtomically(List.copyOf(records));
        return new MigrationResult(records.size(), skippedActive,
                records.stream().map(LegacyTaskMigrationTarget.TaskRecord::taskId).toList());
    }

    private LegacyTaskMigrationTarget.TaskRecord archive(TaskState state) {
        byte[] snapshot;
        try {
            snapshot = objectMapper.writeValueAsBytes(state.view());
        } catch (Exception exception) {
            throw new IllegalStateException("legacy task snapshot cannot be serialized", exception);
        }
        String attemptId = "legacy-" + state.id;
        String digest = sha256(snapshot);
        EvidenceRepository.StoredEvidence stored = evidenceRepository.store(new EvidenceRepository.StoreRequest(
                state.id, attemptId, "legacy-task-snapshot", "application/json", snapshot, digest, "workflow"));
        if (!digest.equals(stored.digest()) || !"COMPLETE".equals(stored.status())) {
            throw new SecurityException("legacy task snapshot was not durably preserved");
        }
        boolean commitVerified = state.sourceCommit != null && state.sourceCommit.matches("[0-9a-f]{40}");
        String repositoryId = "legacy-" + sha256(state.request.repositoryUrl().getBytes(StandardCharsets.UTF_8))
                .substring(0, 32);
        return new LegacyTaskMigrationTarget.TaskRecord(state.id, repositoryId, attemptId,
                commitVerified ? state.sourceCommit : UNKNOWN_COMMIT, commitVerified,
                sha256(state.request.requirement().getBytes(StandardCharsets.UTF_8)), targetStatus(state.status),
                state.status.name(), state.createdAt, state.updatedAt, stored.uri(), stored.digest(),
                stored.classification());
    }

    private static String targetStatus(TaskStatus status) {
        return switch (status) {
            case PR_CREATED -> "COMPLETED";
            case CANCELLED -> "CANCELLED";
            case FAILED -> "FAILED";
            default -> throw new IllegalArgumentException("legacy task is not terminal");
        };
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record MigrationResult(int imported, int skippedActive, List<String> importedTaskIds) {
        public MigrationResult {
            importedTaskIds = List.copyOf(importedTaskIds);
        }
    }
}
