package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.config.SandboxExecutionProperties;
import com.example.aifactory.sandbox.model.SandboxModels.*;
import com.example.aifactory.sandbox.service.SandboxJobStore.JobSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Service
public class SandboxJobService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxJobService.class);
    private static final Pattern TASK_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern EXECUTION_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(password|secret|token|api[_-]?key)([\\s\"']*[:=][\\s\"']*)([^\\s\"']+)");

    private final SandboxExecutionProperties properties;
    private final SandboxRuntime runtime;
    private final SandboxJobStore store;
    private final Clock clock;
    private final ExecutorService executor;
    private final ScheduledExecutorService maintenance;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();
    private final Counter submitted;
    private final Counter completed;
    private final Counter failed;

    public SandboxJobService(SandboxExecutionProperties properties, SandboxRuntime runtime, MeterRegistry metrics,
                             SandboxJobStore store, Clock clock) {
        this.properties = properties;
        this.runtime = runtime;
        this.store = store;
        this.clock = clock;
        this.executor = Executors.newFixedThreadPool(Math.max(1, properties.maxConcurrentJobs()));
        this.maintenance = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "sandbox-job-retention");
            thread.setDaemon(true);
            return thread;
        });
        this.submitted = Counter.builder("ai_factory_sandbox_jobs_submitted").register(metrics);
        this.completed = Counter.builder("ai_factory_sandbox_jobs_completed").register(metrics);
        this.failed = Counter.builder("ai_factory_sandbox_jobs_failed").register(metrics);
        restorePersistedJobs();
        long sweepMillis = Math.max(1_000L, Math.min(Duration.ofMinutes(1).toMillis(),
                properties.jobRetention().toMillis() / 4));
        maintenance.scheduleWithFixedDelay(this::sweepExpiredJobs, sweepMillis, sweepMillis, TimeUnit.MILLISECONDS);
    }

    public ExecutionView submit(Operation operation, StartExecutionRequest request) throws Exception {
        validateRequest(request);
        Path workspace = workspace(request.taskId(), request.sourceCommit());
        verifyPatchDigest(operation, workspace, request.patchDigest());
        pruneExpiredJobs();
        String idempotencyScope = request.taskId() + '|' + operation + '|' + request.idempotencyKey();
        String existingId = idempotency.get(idempotencyScope);
        if (existingId != null) {
            return view(requireOwnedJob(request.taskId(), request.sourceCommit(), existingId));
        }
        pruneCompletedJobs();
        if (jobs.size() >= properties.maxJobs()) {
            throw new IllegalStateException("sandbox job capacity is exhausted");
        }
        Job job = new Job(randomId(), request.taskId(), request.sourceCommit(), request.patchDigest(),
                request.idempotencyKey(), operation, Instant.now(clock));
        String racedId = idempotency.putIfAbsent(idempotencyScope, job.executionId);
        if (racedId != null) {
            return view(requireOwnedJob(request.taskId(), request.sourceCommit(), racedId));
        }
        jobs.put(job.executionId, job);
        try {
            store.save(snapshot(job));
        } catch (RuntimeException exception) {
            jobs.remove(job.executionId, job);
            idempotency.remove(idempotencyScope, job.executionId);
            throw exception;
        }
        submitted.increment();
        job.future = executor.submit(() -> run(job, workspace));
        return view(job);
    }

    public ExecutionView get(String schemaVersion, String taskId, String sourceCommit, String actor,
                             String traceId, String executionId) {
        validateLookup(schemaVersion, taskId, sourceCommit, actor, traceId, executionId);
        pruneExpiredJobs();
        return view(requireOwnedJob(taskId, sourceCommit, executionId));
    }

    public ExecutionView cancel(String schemaVersion, String taskId, String sourceCommit, String actor,
                                String traceId, String executionId) {
        validateLookup(schemaVersion, taskId, sourceCommit, actor, traceId, executionId);
        pruneExpiredJobs();
        Job job = requireOwnedJob(taskId, sourceCommit, executionId);
        JobSnapshot cancelled;
        synchronized (job) {
            if (terminal(job.status)) {
                return view(job);
            }
            job.status = ExecutionStatus.CANCELLED;
            job.verdict = Verdict.INDETERMINATE;
            job.error = "sandbox execution cancelled";
            job.completedAt = Instant.now(clock);
            if (job.future != null) {
                job.future.cancel(true);
            }
            cancelled = snapshot(job);
        }
        try {
            store.save(cancelled);
        } finally {
            runtime.cancel(executionId);
        }
        failed.increment();
        return view(job);
    }

    private void run(Job job, Path workspace) {
        try {
            synchronized (job) {
                if (job.status == ExecutionStatus.CANCELLED) {
                    return;
                }
                job.status = ExecutionStatus.RUNNING;
                job.startedAt = Instant.now(clock);
                store.save(snapshot(job));
            }
            SandboxRuntime.RuntimeResult result = runtime.execute(job.operation, job.executionId, workspace);
            synchronized (job) {
                if (job.status == ExecutionStatus.CANCELLED) {
                    return;
                }
                job.exitCode = result.exitCode();
                job.output = redact(result.output());
                job.status = ExecutionStatus.SUCCEEDED;
                job.verdict = result.exitCode() == 0 ? Verdict.PASSED : Verdict.REJECTED;
                job.completedAt = Instant.now(clock);
                store.save(snapshot(job));
            }
            completed.increment();
        } catch (SandboxRuntime.RuntimeTimeoutException exception) {
            fail(job, ExecutionStatus.TIMED_OUT, "sandbox profile timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            synchronized (job) {
                if (job.status != ExecutionStatus.CANCELLED) {
                    failLocked(job, ExecutionStatus.CANCELLED, "sandbox execution interrupted");
                }
            }
        } catch (Exception exception) {
            fail(job, ExecutionStatus.FAILED, "sandbox runtime unavailable");
        }
    }

    private void fail(Job job, ExecutionStatus status, String error) {
        synchronized (job) {
            if (job.status == ExecutionStatus.CANCELLED) {
                return;
            }
            failLocked(job, status, error);
        }
    }

    private void failLocked(Job job, ExecutionStatus status, String error) {
        job.status = status;
        job.verdict = Verdict.INDETERMINATE;
        job.error = error;
        job.completedAt = Instant.now(clock);
        store.save(snapshot(job));
        failed.increment();
    }

    private Path workspace(String taskId, String sourceCommit) throws Exception {
        Path root = properties.workspaceRoot().toAbsolutePath().normalize().toRealPath();
        Path candidate = root.resolve(taskId).normalize();
        if (!candidate.startsWith(root) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("unknown task workspace");
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root)) {
            throw new IllegalArgumentException("task workspace escapes configured root");
        }
        Process process = new ProcessBuilder("git", "-c", "safe.directory=" + real,
                "-C", real.toString(), "rev-parse", "HEAD").redirectErrorStream(true).start();
        boolean done = process.waitFor(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS);
        if (!done) {
            process.destroyForcibly();
            throw new IllegalStateException("git commit lookup timed out");
        }
        String actual = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.exitValue() != 0 || !actual.equals(sourceCommit)) {
            throw new IllegalArgumentException("source commit does not match task workspace");
        }
        return real;
    }

    private void verifyPatchDigest(Operation operation, Path workspace, String expected) throws Exception {
        boolean required = operation == Operation.VALIDATE_PATCH || operation == Operation.APPLY_PATCH;
        if (expected == null || expected.isBlank()) {
            if (required) {
                throw new IllegalArgumentException("patch_digest is required for patch operations");
            }
            return;
        }
        if (!DIGEST.matcher(expected).matches()) {
            throw new IllegalArgumentException("invalid patch_digest");
        }
        Path patch = workspace.resolve("changes.patch");
        if (!Files.isRegularFile(patch, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("changes.patch is unavailable");
        }
        if (Files.size(patch) > properties.maxPatchBytes()) {
            throw new IllegalArgumentException("changes.patch exceeds the configured size limit");
        }
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(patch)));
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("patch digest does not match workspace");
        }
    }

    private static void validateRequest(StartExecutionRequest request) {
        if (request == null || !"1".equals(request.schemaVersion())) {
            throw new IllegalArgumentException("unsupported schema_version");
        }
        if (request.taskId() == null || !TASK_ID.matcher(request.taskId()).matches()) {
            throw new IllegalArgumentException("invalid task_id");
        }
        if (request.sourceCommit() == null || !COMMIT.matcher(request.sourceCommit()).matches()) {
            throw new IllegalArgumentException("invalid source_commit");
        }
        if (!"workflow".equals(request.actor())) {
            throw new SecurityException("only the deterministic workflow may start sandbox jobs");
        }
        if (request.traceId() == null || !TRACE_ID.matcher(request.traceId()).matches()) {
            throw new IllegalArgumentException("invalid trace_id");
        }
        if (request.idempotencyKey() == null || request.idempotencyKey().length() < 16
                || request.idempotencyKey().length() > 256) {
            throw new IllegalArgumentException("invalid idempotency_key");
        }
    }

    private static void validateLookup(String schemaVersion, String taskId, String sourceCommit, String actor,
                                       String traceId, String executionId) {
        if (!"1".equals(schemaVersion) || taskId == null || !TASK_ID.matcher(taskId).matches()
                || sourceCommit == null || !COMMIT.matcher(sourceCommit).matches() || !"workflow".equals(actor)
                || traceId == null || !TRACE_ID.matcher(traceId).matches()
                || executionId == null || !EXECUTION_ID.matcher(executionId).matches()) {
            throw new IllegalArgumentException("invalid execution lookup context");
        }
    }

    private Job requireOwnedJob(String taskId, String sourceCommit, String executionId) {
        Job job = jobs.get(executionId);
        if (job == null || !job.taskId.equals(taskId) || !job.sourceCommit.equals(sourceCommit)) {
            throw new IllegalArgumentException("unknown execution");
        }
        return job;
    }

    private void pruneCompletedJobs() {
        if (jobs.size() < properties.maxJobs()) {
            return;
        }
        jobs.values().stream()
                .filter(job -> terminal(job.status))
                .sorted(Comparator.comparing(job -> job.completedAt))
                .limit(Math.max(1, properties.maxJobs() / 10L))
                .forEach(job -> {
                    jobs.remove(job.executionId, job);
                    idempotency.entrySet().removeIf(entry -> entry.getValue().equals(job.executionId));
                    store.delete(job.executionId);
                });
    }

    private void pruneExpiredJobs() {
        Instant now = Instant.now(clock);
        for (Job job : jobs.values()) {
            synchronized (job) {
                if (!expired(job.status, job.completedAt, now)) {
                    continue;
                }
                store.delete(job.executionId);
                jobs.remove(job.executionId, job);
                idempotency.remove(idempotencyScope(job.taskId, job.operation, job.idempotencyKey), job.executionId);
            }
        }
    }

    private void sweepExpiredJobs() {
        try {
            pruneExpiredJobs();
        } catch (RuntimeException exception) {
            LOGGER.warn("Sandbox job retention sweep failed; it will be retried", exception);
        }
    }

    private boolean expired(ExecutionStatus status, Instant completedAt, Instant now) {
        return terminal(status) && completedAt != null
                && !completedAt.plus(properties.jobRetention()).isAfter(now);
    }

    private static boolean terminal(ExecutionStatus status) {
        return status == ExecutionStatus.SUCCEEDED || status == ExecutionStatus.FAILED
                || status == ExecutionStatus.TIMED_OUT || status == ExecutionStatus.CANCELLED;
    }

    private String redact(String output) {
        if (output == null) {
            return null;
        }
        String redacted = SECRET.matcher(output).replaceAll("$1$2[REDACTED]");
        for (String secret : new String[]{properties.artifactoryToken(), properties.sonarToken()}) {
            if (secret != null && !secret.isBlank()) {
                redacted = redacted.replace(secret, "[REDACTED]");
            }
        }
        return redacted;
    }

    private static String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void restorePersistedJobs() {
        try {
            runtime.reconcileOrphans();
        } catch (Exception exception) {
            throw new IllegalStateException("sandbox orphan reconciliation failed", exception);
        }
        Instant now = Instant.now(clock);
        for (JobSnapshot persisted : store.load()) {
            if (expired(persisted.status(), persisted.completedAt(), now)) {
                store.delete(persisted.executionId());
                continue;
            }
            Job job = new Job(persisted.executionId(), persisted.taskId(), persisted.sourceCommit(),
                    persisted.patchDigest(), persisted.idempotencyKey(), persisted.operation(), persisted.createdAt());
            job.status = persisted.status();
            job.verdict = persisted.verdict();
            job.exitCode = persisted.exitCode();
            job.output = persisted.output();
            job.error = persisted.error();
            job.startedAt = persisted.startedAt();
            job.completedAt = persisted.completedAt();
            if (!terminal(job.status)) {
                job.status = ExecutionStatus.FAILED;
                job.verdict = Verdict.INDETERMINATE;
                job.exitCode = null;
                job.error = "sandbox controller restarted during execution";
                job.completedAt = Instant.now(clock);
                store.save(snapshot(job));
            }
            Job previous = jobs.putIfAbsent(job.executionId, job);
            String scope = idempotencyScope(job.taskId, job.operation, job.idempotencyKey);
            String previousExecution = idempotency.putIfAbsent(scope, job.executionId);
            if (previous != null || (previousExecution != null && !previousExecution.equals(job.executionId))) {
                throw new IllegalStateException("duplicate persisted sandbox job identity");
            }
        }
    }

    private static String idempotencyScope(String taskId, Operation operation, String idempotencyKey) {
        return taskId + '|' + operation + '|' + idempotencyKey;
    }

    private static JobSnapshot snapshot(Job job) {
        return JobSnapshot.versionOne(job.executionId, job.taskId, job.sourceCommit, job.patchDigest,
                job.idempotencyKey, job.operation, job.status, job.verdict, job.exitCode, job.output, job.error,
                job.createdAt, job.startedAt, job.completedAt);
    }

    private static ExecutionView view(Job job) {
        synchronized (job) {
            return new ExecutionView(job.executionId, job.taskId, job.operation, job.status, job.verdict,
                    job.exitCode, job.output, job.error, job.createdAt, job.startedAt, job.completedAt);
        }
    }

    @PreDestroy
    void shutdown() {
        maintenance.shutdownNow();
        executor.shutdownNow();
    }

    private static final class Job {
        private final String executionId;
        private final String taskId;
        private final String sourceCommit;
        private final String patchDigest;
        private final String idempotencyKey;
        private final Operation operation;
        private final Instant createdAt;
        private ExecutionStatus status = ExecutionStatus.ACCEPTED;
        private Verdict verdict = Verdict.PENDING;
        private Integer exitCode;
        private String output;
        private String error;
        private Instant startedAt;
        private Instant completedAt;
        private Future<?> future;

        private Job(String executionId, String taskId, String sourceCommit, String patchDigest, String idempotencyKey,
                    Operation operation, Instant createdAt) {
            this.executionId = executionId;
            this.taskId = taskId;
            this.sourceCommit = sourceCommit;
            this.patchDigest = patchDigest;
            this.idempotencyKey = idempotencyKey;
            this.operation = operation;
            this.createdAt = createdAt;
        }
    }
}
