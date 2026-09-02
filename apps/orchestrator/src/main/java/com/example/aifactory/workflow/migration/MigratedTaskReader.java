package com.example.aifactory.workflow.migration;

import com.example.aifactory.model.TaskStatus;
import com.example.aifactory.model.TaskView;
import com.example.aifactory.workflow.EvidenceRepository;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/** Compatibility reader for immutable legacy views archived in Evidence MCP. */
public final class MigratedTaskReader {
    private static final Set<TaskStatus> TERMINAL = Set.of(TaskStatus.PR_CREATED, TaskStatus.CANCELLED, TaskStatus.FAILED);
    private final EvidenceRepository evidenceRepository;
    private final ObjectMapper objectMapper;

    public MigratedTaskReader(EvidenceRepository evidenceRepository, ObjectMapper objectMapper) {
        this.evidenceRepository = evidenceRepository;
        this.objectMapper = objectMapper;
    }

    public TaskView read(LegacyTaskMigrationTarget.TaskRecord record) {
        EvidenceRepository.RawEvidence evidence = evidenceRepository.read(new EvidenceRepository.ReadRequest(
                record.taskId(), record.attemptId(), record.snapshotUri(), "workflow",
                "serve migrated terminal task"));
        String digest = sha256(evidence.content());
        if (!record.snapshotUri().equals(evidence.uri()) || !record.snapshotDigest().equals(evidence.digest())
                || !digest.equals(evidence.digest()) || !"COMPLETE".equals(evidence.status())) {
            throw new SecurityException("migrated task snapshot failed evidence verification");
        }
        TaskView view;
        try {
            view = objectMapper.readValue(evidence.content(), TaskView.class);
        } catch (Exception exception) {
            throw new IllegalStateException("migrated task snapshot cannot be decoded", exception);
        }
        if (!record.taskId().equals(view.id()) || !record.legacyStatus().equals(view.status().name())
                || !TERMINAL.contains(view.status())) {
            throw new SecurityException("migrated task snapshot diverges from durable metadata");
        }
        return view;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
