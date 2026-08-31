package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.config.SandboxExecutionProperties;
import com.example.aifactory.sandbox.model.SandboxModels.ExecutionStatus;
import com.example.aifactory.sandbox.model.SandboxModels.Operation;
import com.example.aifactory.sandbox.model.SandboxModels.Verdict;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class SandboxJobStore {
    private static final int FORMAT_VERSION = 1;
    private static final Pattern EXECUTION_ID = Pattern.compile("^[0-9a-f]{32}$");

    private final ObjectMapper objectMapper;
    private final Path stateRoot;

    public SandboxJobStore(ObjectMapper objectMapper, SandboxExecutionProperties properties) {
        this.objectMapper = objectMapper;
        this.stateRoot = initialize(properties.stateRoot());
    }

    public synchronized List<JobSnapshot> load() {
        try (var paths = Files.list(stateRoot)) {
            List<Path> snapshots = paths
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            List<JobSnapshot> loaded = new ArrayList<>(snapshots.size());
            for (Path path : snapshots) {
                JobSnapshot snapshot = objectMapper.readValue(path.toFile(), JobSnapshot.class);
                validate(snapshot, path);
                loaded.add(snapshot);
            }
            return List.copyOf(loaded);
        } catch (IOException exception) {
            throw new IllegalStateException("sandbox job state is unreadable", exception);
        }
    }

    public synchronized void save(JobSnapshot snapshot) {
        validate(snapshot, null);
        Path target = stateRoot.resolve(snapshot.executionId() + ".json");
        Path temporary = stateRoot.resolve(snapshot.executionId() + ".tmp-" + UUID.randomUUID());
        try {
            byte[] content = objectMapper.writeValueAsBytes(snapshot);
            Files.write(temporary, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("sandbox job state cannot be persisted", exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // A uniquely named incomplete file is never loaded as a job snapshot.
            }
        }
    }

    public synchronized void delete(String executionId) {
        requireExecutionId(executionId);
        try {
            Files.deleteIfExists(stateRoot.resolve(executionId + ".json"));
        } catch (IOException exception) {
            throw new IllegalStateException("sandbox job state cannot be deleted", exception);
        }
    }

    private static Path initialize(Path configuredRoot) {
        if (configuredRoot == null) {
            throw new IllegalArgumentException("sandbox state root is required");
        }
        try {
            Files.createDirectories(configuredRoot);
            if (Files.isSymbolicLink(configuredRoot)) {
                throw new IllegalArgumentException("sandbox state root must not be a symbolic link");
            }
            Path real = configuredRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(real)) {
                throw new IllegalArgumentException("sandbox state root must be a writable directory");
            }
            return real;
        } catch (IOException exception) {
            throw new IllegalStateException("sandbox state root is unavailable", exception);
        }
    }

    private static void validate(JobSnapshot snapshot, Path source) {
        if (snapshot == null || snapshot.formatVersion() != FORMAT_VERSION) {
            throw invalid(source, "unsupported sandbox job state format");
        }
        requireExecutionId(snapshot.executionId());
        if (snapshot.taskId() == null || snapshot.sourceCommit() == null || snapshot.idempotencyKey() == null
                || snapshot.operation() == null || snapshot.status() == null || snapshot.verdict() == null
                || snapshot.createdAt() == null) {
            throw invalid(source, "incomplete sandbox job state");
        }
        if (source != null && !source.getFileName().toString().equals(snapshot.executionId() + ".json")) {
            throw invalid(source, "sandbox job state filename does not match its execution id");
        }
        boolean terminal = snapshot.status() == ExecutionStatus.SUCCEEDED
                || snapshot.status() == ExecutionStatus.FAILED
                || snapshot.status() == ExecutionStatus.TIMED_OUT
                || snapshot.status() == ExecutionStatus.CANCELLED;
        if (terminal != (snapshot.completedAt() != null)) {
            throw invalid(source, "sandbox job completion timestamp is inconsistent with its status");
        }
        if (snapshot.startedAt() != null && snapshot.startedAt().isBefore(snapshot.createdAt())) {
            throw invalid(source, "sandbox job start timestamp precedes creation");
        }
        if (snapshot.completedAt() != null && (snapshot.completedAt().isBefore(snapshot.createdAt())
                || snapshot.startedAt() != null && snapshot.completedAt().isBefore(snapshot.startedAt()))) {
            throw invalid(source, "sandbox job completion timestamp is inconsistent");
        }
        if (snapshot.heartbeatAt() != null && (snapshot.heartbeatAt().isBefore(snapshot.createdAt())
                || snapshot.completedAt() != null && snapshot.heartbeatAt().isAfter(snapshot.completedAt()))) {
            throw invalid(source, "sandbox job heartbeat timestamp is inconsistent");
        }
    }

    private static void requireExecutionId(String executionId) {
        if (executionId == null || !EXECUTION_ID.matcher(executionId).matches()) {
            throw new IllegalArgumentException("invalid persisted sandbox execution id");
        }
    }

    private static IllegalArgumentException invalid(Path source, String message) {
        return new IllegalArgumentException(source == null ? message : message + ": " + source.getFileName());
    }

    public record JobSnapshot(
            int formatVersion,
            String executionId,
            String taskId,
            String sourceCommit,
            String patchDigest,
            String idempotencyKey,
            Operation operation,
            ExecutionStatus status,
            Verdict verdict,
            Integer exitCode,
            String output,
            boolean outputTruncated,
            String error,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant heartbeatAt) {

        public static JobSnapshot versionOne(String executionId, String taskId, String sourceCommit,
                                             String patchDigest, String idempotencyKey, Operation operation,
                                             ExecutionStatus status, Verdict verdict, Integer exitCode,
                                             String output, boolean outputTruncated, String error, Instant createdAt,
                                             Instant startedAt, Instant completedAt, Instant heartbeatAt) {
            return new JobSnapshot(FORMAT_VERSION, executionId, taskId, sourceCommit, patchDigest, idempotencyKey,
                    operation, status, verdict, exitCode, output, outputTruncated, error, createdAt, startedAt,
                    completedAt, heartbeatAt);
        }

        public static JobSnapshot versionOne(String executionId, String taskId, String sourceCommit,
                                             String patchDigest, String idempotencyKey, Operation operation,
                                             ExecutionStatus status, Verdict verdict, Integer exitCode,
                                             String output, String error, Instant createdAt, Instant startedAt,
                                             Instant completedAt) {
            return versionOne(executionId, taskId, sourceCommit, patchDigest, idempotencyKey, operation, status,
                    verdict, exitCode, output, false, error, createdAt, startedAt, completedAt, null);
        }
    }
}
