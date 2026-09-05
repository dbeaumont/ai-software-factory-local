package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.config.SandboxExecutionProperties;
import com.example.aifactory.sandbox.model.SandboxModels.*;
import com.example.aifactory.sandbox.service.SandboxJobStore.JobSnapshot;
import com.example.aifactory.sandbox.service.SandboxJobStore.JobManifest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Service
public class SandboxJobService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxJobService.class);
    private static final Pattern TASK_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern TRACE_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern TRACEPARENT = Pattern.compile("^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$");
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern EXECUTION_ID = Pattern.compile("^[0-9a-f]{32}$");
    private static final int DEFAULT_OUTPUT_PAGE_CHARS = 4_096;
    private static final int MAX_OUTPUT_PAGE_CHARS = 16_384;
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(password|secret|token|api[_-]?key)([\\s\"']*[:=][\\s\"']*)([^\\s\"']+)");

    private final SandboxExecutionProperties properties;
    private final SandboxRuntime runtime;
    private final SandboxJobStore store;
    private final Clock clock;
    private final ThreadPoolExecutor executor;
    private final ScheduledExecutorService maintenance;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final Map<String, String> idempotency = new ConcurrentHashMap<>();
    private final Object admissionLock = new Object();
    private final Counter submitted;
    private final Counter completed;
    private final Counter failed;
    private final Counter rejectedGlobal;
    private final Counter rejectedTask;
    private final Counter rejectedRetention;
    private final Counter maintenanceFailures;
    private final Timer queueDuration;
    private final ObservationRegistry observations;

    @Autowired
    public SandboxJobService(SandboxExecutionProperties properties, SandboxRuntime runtime, MeterRegistry metrics,
                             SandboxJobStore store, Clock clock, ObservationRegistry observations) {
        this.properties = properties;
        this.runtime = runtime;
        this.store = store;
        this.clock = clock;
        this.observations = observations;
        BlockingQueue<Runnable> queue = properties.maxQueuedJobs() == 0
                ? new SynchronousQueue<>()
                : new ArrayBlockingQueue<>(properties.maxQueuedJobs());
        this.executor = new ThreadPoolExecutor(properties.maxConcurrentJobs(), properties.maxConcurrentJobs(),
                0L, TimeUnit.MILLISECONDS, queue, new ThreadPoolExecutor.AbortPolicy());
        this.maintenance = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "sandbox-job-retention");
            thread.setDaemon(true);
            return thread;
        });
        this.submitted = Counter.builder("ai_factory_sandbox_jobs_submitted").register(metrics);
        this.completed = Counter.builder("ai_factory_sandbox_jobs_completed").register(metrics);
        this.failed = Counter.builder("ai_factory_sandbox_jobs_failed").register(metrics);
        this.rejectedGlobal = Counter.builder("ai_factory_sandbox_jobs_rejected")
                .tag("reason", "global_capacity").register(metrics);
        this.rejectedTask = Counter.builder("ai_factory_sandbox_jobs_rejected")
                .tag("reason", "task_quota").register(metrics);
        this.rejectedRetention = Counter.builder("ai_factory_sandbox_jobs_rejected")
                .tag("reason", "retention_capacity").register(metrics);
        this.maintenanceFailures = Counter.builder("ai_factory_sandbox_maintenance_failures").register(metrics);
        this.queueDuration = Timer.builder("ai_factory_sandbox_job_queue_duration").register(metrics);
        Gauge.builder("ai_factory_sandbox_jobs_running", executor, ThreadPoolExecutor::getActiveCount)
                .register(metrics);
        Gauge.builder("ai_factory_sandbox_jobs_queued", executor, value -> value.getQueue().size())
                .register(metrics);
        restorePersistedJobs();
        long maintenanceMillis = Math.max(1_000L, Math.min(properties.heartbeatInterval().toMillis(),
                Math.min(Duration.ofMinutes(1).toMillis(), properties.jobRetention().toMillis() / 4)));
        maintenance.scheduleWithFixedDelay(this::maintainJobs, maintenanceMillis, maintenanceMillis,
                TimeUnit.MILLISECONDS);
    }

    SandboxJobService(SandboxExecutionProperties properties, SandboxRuntime runtime, MeterRegistry metrics,
                      SandboxJobStore store, Clock clock) {
        this(properties, runtime, metrics, store, clock, ObservationRegistry.NOOP);
    }

    public ExecutionView submit(Operation operation, StartExecutionRequest request) throws Exception {
        validateRequest(request);
        Path workspace = workspace(request.taskId(), request.sourceCommit());
        verifyPatchDigest(operation, workspace, request.patchDigest());
        JobManifest manifest = manifest(operation, request.taskId(), workspace);
        pruneExpiredJobs();
        String idempotencyScope = idempotencyScope(request.taskId(), operation, request.idempotencyKey());
        synchronized (admissionLock) {
            String existingId = idempotency.get(idempotencyScope);
            if (existingId != null) {
                Job existing = requireOwnedJob(request.taskId(), request.sourceCommit(), existingId);
                if (!java.util.Objects.equals(existing.patchDigest, request.patchDigest())) {
                    throw new IllegalStateException("idempotency key conflicts with a different input digest");
                }
                return view(existing);
            }
            pruneCompletedJobs();
            if (jobs.size() >= properties.maxJobs()) {
                rejectedRetention.increment();
                throw new IllegalStateException("sandbox job retention capacity is exhausted");
            }
            long activeJobs = activeJobCount(null);
            if (activeJobs >= (long) properties.maxConcurrentJobs() + properties.maxQueuedJobs()) {
                rejectedGlobal.increment();
                throw new IllegalStateException("sandbox execution queue is saturated");
            }
            if (activeJobCount(request.taskId()) >= properties.maxActiveJobsPerTask()) {
                rejectedTask.increment();
                throw new IllegalStateException("sandbox task active job quota is exhausted");
            }
            Job job = new Job(randomId(), request.taskId(), request.sourceCommit(), request.patchDigest(),
                    request.idempotencyKey(), operation, manifest, Instant.now(clock));
            job.observation = Observation.createNotStarted("ai.factory.sandbox.job", observations)
                    .contextualName("sandbox " + operation.name().toLowerCase(java.util.Locale.ROOT))
                    .lowCardinalityKeyValue("ai.operation", operation.name().toLowerCase(java.util.Locale.ROOT))
                    .lowCardinalityKeyValue("ai.outcome", "accepted")
                    .highCardinalityKeyValue("ai.task.id", request.taskId())
                    .highCardinalityKeyValue("sandbox.execution.id", job.executionId)
                    .start();
            job.observation.event(Observation.Event.of("queued"));
            idempotency.put(idempotencyScope, job.executionId);
            jobs.put(job.executionId, job);
            try {
                store.save(snapshot(job));
                job.future = executor.submit(() -> run(job, workspace));
            } catch (RejectedExecutionException exception) {
                rollbackAdmission(job, idempotencyScope);
                rejectedGlobal.increment();
                stopObservation(job, exception, "rejected");
                throw new IllegalStateException("sandbox execution queue is saturated", exception);
            } catch (RuntimeException exception) {
                rollbackAdmission(job, idempotencyScope);
                stopObservation(job, exception, "failed");
                throw exception;
            }
            submitted.increment();
            return view(job);
        }
    }

    private JobManifest manifest(Operation operation, String taskId, Path workspace) {
        SandboxProfiles.Profile profile = SandboxProfiles.forOperation(operation, workspace);
        return new JobManifest(profile.id(), properties.image(), properties.imageDigest(),
                taskId, 2L * 1024 * 1024 * 1024, 2, 512, profile.timeout().toSeconds(),
                profile.network(), profile.workspaceReadOnly(), profile.mavenCache(), profile.environmentNames());
    }

    private void rollbackAdmission(Job job, String idempotencyScope) {
        jobs.remove(job.executionId, job);
        idempotency.remove(idempotencyScope, job.executionId);
        store.delete(job.executionId);
    }

    private long activeJobCount(String taskId) {
        return jobs.values().stream().filter(job -> {
            synchronized (job) {
                return !terminal(job.status) && (taskId == null || taskId.equals(job.taskId));
            }
        }).count();
    }

    public ExecutionView get(String schemaVersion, String taskId, String sourceCommit, String actor,
                             String traceId, String executionId) {
        return get(schemaVersion, taskId, sourceCommit, actor, traceId, executionId, null, null);
    }

    public ExecutionView get(String schemaVersion, String taskId, String sourceCommit, String actor,
                             String traceId, String executionId, Integer outputCursor, Integer outputLimit) {
        return get(schemaVersion, taskId, "attempt-test", sourceCommit, actor, traceId,
                traceparent(traceId), deadline(), executionId, outputCursor, outputLimit);
    }

    public ExecutionView get(String schemaVersion, String taskId, String attemptId, String sourceCommit, String actor,
                             String traceId, String traceparent, String deadline, String executionId,
                             Integer outputCursor, Integer outputLimit) {
        validateLookup(schemaVersion, taskId, attemptId, sourceCommit, actor, traceId, traceparent, deadline,
                executionId);
        pruneExpiredJobs();
        return view(requireOwnedJob(taskId, sourceCommit, executionId), outputCursor, outputLimit);
    }

    public ExecutionView cancel(String schemaVersion, String taskId, String sourceCommit, String actor,
                                String traceId, String executionId) {
        return cancel(schemaVersion, taskId, "attempt-test", sourceCommit, actor, traceId,
                traceparent(traceId), deadline(), executionId);
    }

    public ExecutionView cancel(String schemaVersion, String taskId, String attemptId, String sourceCommit,
                                String actor, String traceId, String traceparent, String deadline,
                                String executionId) {
        validateLookup(schemaVersion, taskId, attemptId, sourceCommit, actor, traceId, traceparent, deadline,
                executionId);
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
            job.heartbeatAt = job.completedAt;
            if (job.future != null) {
                job.future.cancel(true);
                executor.purge();
            }
            cancelled = snapshot(job);
        }
        try {
            store.save(cancelled);
        } finally {
            runtime.cancel(executionId);
        }
        failed.increment();
        stopObservation(job, null, "cancelled");
        return view(job);
    }

    private void run(Job job, Path workspace) {
        try (Observation.Scope ignored = openObservation(job)) {
            synchronized (job) {
                if (job.status == ExecutionStatus.CANCELLED) {
                    return;
                }
                job.status = ExecutionStatus.RUNNING;
                job.startedAt = Instant.now(clock);
                job.heartbeatAt = job.startedAt;
                queueDuration.record(Duration.between(job.createdAt, job.startedAt));
                event(job, "running");
                store.save(snapshot(job));
            }
            SandboxRuntime.RuntimeResult result = runtime.execute(job.operation, job.executionId, workspace);
            synchronized (job) {
                if (job.status == ExecutionStatus.CANCELLED) {
                    return;
                }
                job.exitCode = result.exitCode();
                job.output = redact(result.output());
                job.outputTruncated = result.outputTruncated();
                job.evidenceStatus = result.outputTruncated() ? EvidenceStatus.PARTIAL : EvidenceStatus.COMPLETE;
                job.outputDigest = outputDigest(job.output);
                job.status = ExecutionStatus.SUCCEEDED;
                job.verdict = result.outputTruncated()
                        ? Verdict.INDETERMINATE
                        : result.exitCode() == 0 ? Verdict.PASSED : Verdict.REJECTED;
                job.completedAt = Instant.now(clock);
                job.heartbeatAt = job.completedAt;
                store.save(snapshot(job));
            }
            completed.increment();
            stopObservation(job, null, job.verdict == Verdict.PASSED ? "succeeded" : "rejected");
        } catch (SandboxRuntime.RuntimeTimeoutException exception) {
            timeout(job, exception);
            stopObservation(job, exception, "timed_out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            synchronized (job) {
                if (job.status != ExecutionStatus.CANCELLED) {
                    failLocked(job, ExecutionStatus.CANCELLED, "sandbox execution interrupted");
                }
            }
            stopObservation(job, exception, "cancelled");
        } catch (Exception exception) {
            fail(job, ExecutionStatus.FAILED, "sandbox runtime unavailable");
            stopObservation(job, exception, "failed");
        }
    }

    private static Observation.Scope openObservation(Job job) {
        return job.observation == null ? null : job.observation.openScope();
    }

    private static void event(Job job, String name) {
        if (job.observation != null) {
            job.observation.event(Observation.Event.of(name));
        }
    }

    private static void stopObservation(Job job, Exception error, String outcome) {
        synchronized (job) {
            if (job.observation == null || job.observationStopped) {
                return;
            }
            job.observation.lowCardinalityKeyValue("ai.outcome", outcome);
            if (error != null) {
                job.observation.error(error);
            }
            job.observation.stop();
            job.observationStopped = true;
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

    private void timeout(Job job, SandboxRuntime.RuntimeTimeoutException exception) {
        synchronized (job) {
            if (job.status == ExecutionStatus.CANCELLED) {
                return;
            }
            job.output = redact(exception.partialOutput());
            job.outputTruncated = exception.outputTruncated();
            job.evidenceStatus = EvidenceStatus.PARTIAL;
            job.outputDigest = outputDigest(job.output);
            failLocked(job, ExecutionStatus.TIMED_OUT, "sandbox profile timed out");
        }
    }

    private void failLocked(Job job, ExecutionStatus status, String error) {
        job.status = status;
        job.verdict = Verdict.INDETERMINATE;
        job.error = error;
        job.completedAt = Instant.now(clock);
        job.heartbeatAt = job.completedAt;
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
        if (request.attemptId() == null || !TASK_ID.matcher(request.attemptId()).matches()) {
            throw new IllegalArgumentException("invalid attempt_id");
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
        validateTraceAndDeadline(request.traceId(), request.traceparent(), request.deadline());
        if (request.idempotencyKey() == null || request.idempotencyKey().length() < 16
                || request.idempotencyKey().length() > 256) {
            throw new IllegalArgumentException("invalid idempotency_key");
        }
    }

    private static void validateLookup(String schemaVersion, String taskId, String attemptId, String sourceCommit,
                                       String actor, String traceId, String traceparent, String deadline,
                                       String executionId) {
        if (!"1".equals(schemaVersion) || taskId == null || !TASK_ID.matcher(taskId).matches()
                || attemptId == null || !TASK_ID.matcher(attemptId).matches()
                || sourceCommit == null || !COMMIT.matcher(sourceCommit).matches() || !"workflow".equals(actor)
                || traceId == null || !TRACE_ID.matcher(traceId).matches()
                || executionId == null || !EXECUTION_ID.matcher(executionId).matches()) {
            throw new IllegalArgumentException("invalid execution lookup context");
        }
        validateTraceAndDeadline(traceId, traceparent, deadline);
    }

    private static void validateTraceAndDeadline(String traceId, String traceparent, String deadline) {
        if (traceparent == null || !TRACEPARENT.matcher(traceparent).matches()
                || !traceparent.substring(3, 35).equals(traceId)) {
            throw new IllegalArgumentException("invalid traceparent");
        }
        try {
            Instant parsed = Instant.parse(deadline);
            Instant now = Instant.now();
            if (!parsed.isAfter(now) || parsed.isAfter(now.plus(Duration.ofHours(24)))) {
                throw new IllegalArgumentException("deadline is expired or exceeds the maximum horizon");
            }
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalArgumentException illegal
                    && illegal.getMessage() != null && illegal.getMessage().startsWith("deadline is")) {
                throw illegal;
            }
            throw new IllegalArgumentException("invalid deadline", exception);
        }
    }

    private static String traceparent(String traceId) {
        return "00-" + traceId + "-0123456789abcdef-01";
    }

    private static String deadline() {
        return Instant.now().plusSeconds(60).toString();
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

    void maintainJobs() {
        try {
            refreshHeartbeats();
            pruneExpiredJobs();
            runtime.reconcileOrphans(activeExecutionIds());
        } catch (Exception exception) {
            maintenanceFailures.increment();
            LOGGER.warn("Sandbox job maintenance failed; it will be retried", exception);
        }
    }

    private Set<String> activeExecutionIds() {
        return jobs.values().stream().filter(job -> {
            synchronized (job) {
                return !terminal(job.status);
            }
        }).map(job -> job.executionId).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private void refreshHeartbeats() {
        Instant now = Instant.now(clock);
        for (Job job : jobs.values()) {
            synchronized (job) {
                boolean active = job.status == ExecutionStatus.ACCEPTED || job.status == ExecutionStatus.RUNNING;
                boolean due = job.heartbeatAt == null
                        || !job.heartbeatAt.isAfter(now.minus(properties.heartbeatInterval()));
                if (active && due) {
                    job.heartbeatAt = now;
                    store.save(snapshot(job));
                }
            }
        }
    }

    private boolean expired(ExecutionStatus status, Instant completedAt, Instant now) {
        return terminal(status) && completedAt != null
                && !completedAt.isAfter(now.minus(properties.jobRetention()));
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

    private static String outputDigest(String output) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((output == null ? "" : output).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private void restorePersistedJobs() {
        try {
            runtime.reconcileOrphans(Set.of());
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
                    persisted.patchDigest(), persisted.idempotencyKey(), persisted.operation(), persisted.manifest(),
                    persisted.createdAt());
            job.status = persisted.status();
            job.verdict = persisted.verdict();
            job.exitCode = persisted.exitCode();
            job.output = persisted.output();
            job.outputTruncated = persisted.outputTruncated();
            job.evidenceStatus = persisted.evidenceStatus();
            job.outputDigest = persisted.outputDigest();
            job.error = persisted.error();
            job.startedAt = persisted.startedAt();
            job.completedAt = persisted.completedAt();
            job.heartbeatAt = persisted.heartbeatAt();
            boolean recoveredInterruptedJob = !terminal(job.status);
            if (recoveredInterruptedJob) {
                job.status = ExecutionStatus.FAILED;
                job.verdict = Verdict.INDETERMINATE;
                job.exitCode = null;
                job.error = "sandbox controller restarted during execution";
                job.completedAt = Instant.now(clock);
                job.heartbeatAt = job.completedAt;
            }
            if (job.evidenceStatus == null) {
                if (job.status == ExecutionStatus.SUCCEEDED) {
                    job.evidenceStatus = job.outputTruncated ? EvidenceStatus.PARTIAL : EvidenceStatus.COMPLETE;
                } else if (job.status == ExecutionStatus.TIMED_OUT || job.output != null) {
                    job.evidenceStatus = EvidenceStatus.PARTIAL;
                } else {
                    job.evidenceStatus = EvidenceStatus.NONE;
                }
                if (job.evidenceStatus == EvidenceStatus.PARTIAL) {
                    job.verdict = Verdict.INDETERMINATE;
                }
                job.outputDigest = job.evidenceStatus == EvidenceStatus.NONE ? null : outputDigest(job.output);
            }
            if (recoveredInterruptedJob || persisted.evidenceStatus() == null) {
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
        return JobSnapshot.versionOneWithManifest(job.executionId, job.taskId, job.sourceCommit, job.patchDigest,
                job.idempotencyKey, job.operation, job.manifest, job.status, job.verdict, job.exitCode, job.output,
                job.outputTruncated, job.evidenceStatus, job.outputDigest, job.error, job.createdAt, job.startedAt,
                job.completedAt, job.heartbeatAt);
    }

    private static ExecutionView view(Job job) {
        return view(job, null, null);
    }

    private static ExecutionView view(Job job, Integer requestedCursor, Integer requestedLimit) {
        synchronized (job) {
            int cursor = requestedCursor == null ? 0 : requestedCursor;
            int limit = requestedLimit == null ? DEFAULT_OUTPUT_PAGE_CHARS : requestedLimit;
            String retained = job.output == null ? "" : job.output;
            if (cursor < 0 || cursor > retained.length()) {
                throw new IllegalArgumentException("invalid output_cursor");
            }
            if (limit < 1 || limit > MAX_OUTPUT_PAGE_CHARS) {
                throw new IllegalArgumentException("invalid output_limit");
            }
            int end = Math.min(retained.length(), cursor + limit);
            String page = job.output == null ? null : retained.substring(cursor, end);
            Integer nextCursor = end < retained.length() ? end : null;
            return new ExecutionView(job.executionId, job.taskId, job.operation, job.status, job.verdict,
                    job.exitCode, page, cursor, nextCursor, retained.length(), job.outputTruncated,
                    job.evidenceStatus, job.outputDigest, job.error, job.createdAt, job.startedAt, job.completedAt,
                    job.heartbeatAt);
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
        private final JobManifest manifest;
        private final Instant createdAt;
        private ExecutionStatus status = ExecutionStatus.ACCEPTED;
        private Verdict verdict = Verdict.PENDING;
        private Integer exitCode;
        private String output;
        private boolean outputTruncated;
        private EvidenceStatus evidenceStatus = EvidenceStatus.NONE;
        private String outputDigest;
        private String error;
        private Instant startedAt;
        private Instant completedAt;
        private Instant heartbeatAt;
        private Future<?> future;
        private Observation observation;
        private boolean observationStopped;

        private Job(String executionId, String taskId, String sourceCommit, String patchDigest, String idempotencyKey,
                    Operation operation, JobManifest manifest, Instant createdAt) {
            this.executionId = executionId;
            this.taskId = taskId;
            this.sourceCommit = sourceCommit;
            this.patchDigest = patchDigest;
            this.idempotencyKey = idempotencyKey;
            this.operation = operation;
            this.manifest = manifest;
            this.createdAt = createdAt;
            this.heartbeatAt = createdAt;
        }
    }
}
