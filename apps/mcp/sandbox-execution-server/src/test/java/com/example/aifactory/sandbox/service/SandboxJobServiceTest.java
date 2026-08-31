package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.config.SandboxExecutionProperties;
import com.example.aifactory.sandbox.model.SandboxModels.*;
import com.example.aifactory.sandbox.service.SandboxJobStore.JobSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SandboxJobServiceTest {
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";

    @TempDir
    Path root;

    private SandboxJobService jobs;
    private FakeRuntime runtime;
    private SandboxExecutionProperties properties;
    private SandboxJobStore store;
    private Clock clock;
    private String commit;
    private String patchDigest;

    @BeforeEach
    void setUp() throws Exception {
        Path repository = root.resolve("task-1");
        Files.createDirectories(repository);
        Files.writeString(repository.resolve("file.txt"), "before\n");
        run(repository, "git", "init", "-q");
        run(repository, "git", "config", "user.email", "test@example.local");
        run(repository, "git", "config", "user.name", "Test");
        run(repository, "git", "add", ".");
        run(repository, "git", "commit", "-qm", "initial");
        commit = output(repository, "git", "rev-parse", "HEAD");
        Path patch = repository.resolve("changes.patch");
        Files.writeString(patch, "diff --git a/file.txt b/file.txt\n");
        patchDigest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(patch)));
        properties = new SandboxExecutionProperties(root, root.resolve(".sandbox-jobs"), "workspace", "image",
                "network", 2, 100, Duration.ofHours(1), Duration.ofSeconds(15),
                65_536, 1_048_576,
                "mirror", "artifact-token", "sonar", "sonar-token");
        store = new SandboxJobStore(new ObjectMapper().findAndRegisterModules(), properties);
        runtime = new FakeRuntime();
        clock = Clock.systemUTC();
        jobs = service(runtime);
    }

    @AfterEach
    void tearDown() {
        jobs.shutdown();
    }

    @Test
    void submitsAsynchronouslyAndDeduplicatesByIdempotencyKey() throws Exception {
        StartExecutionRequest request = request("workflow", "stable-key-for-patch-check", patchDigest);

        ExecutionView first = jobs.submit(Operation.VALIDATE_PATCH, request);
        ExecutionView second = jobs.submit(Operation.VALIDATE_PATCH, request);
        ExecutionView completed = await(first.executionId());

        assertEquals(first.executionId(), second.executionId());
        assertEquals(ExecutionStatus.SUCCEEDED, completed.status());
        assertEquals(Verdict.PASSED, completed.verdict());
        assertEquals(1, runtime.calls.get());
    }

    @Test
    void dispatchesApplyPatchAsItsOwnIdempotentOperation() throws Exception {
        StartExecutionRequest request = request("workflow", "stable-key-for-patch-apply", patchDigest);

        ExecutionView first = jobs.submit(Operation.APPLY_PATCH, request);
        ExecutionView duplicate = jobs.submit(Operation.APPLY_PATCH, request);
        ExecutionView completed = await(first.executionId());

        assertEquals(first.executionId(), duplicate.executionId());
        assertEquals(Operation.APPLY_PATCH, completed.operation());
        assertEquals(ExecutionStatus.SUCCEEDED, completed.status());
        assertEquals(Operation.APPLY_PATCH, runtime.lastOperation);
        assertEquals(1, runtime.calls.get());
    }

    @Test
    void separatesACompletedExecutionFromARejectedBusinessVerdictAndRedactsOutput() throws Exception {
        runtime.result = new SandboxRuntime.RuntimeResult(1,
                "SONAR_TOKEN=top-secret\nraw artifact-token must also disappear\ntests failed\n");

        ExecutionView submitted = jobs.submit(Operation.RUN_TESTS,
                request("workflow", "stable-key-for-tests-run", patchDigest));
        ExecutionView completed = await(submitted.executionId());

        assertEquals(ExecutionStatus.SUCCEEDED, completed.status());
        assertEquals(Verdict.REJECTED, completed.verdict());
        assertEquals(1, completed.exitCode());
        assertFalse(completed.output().contains("top-secret"));
        assertFalse(completed.output().contains("artifact-token"));
        assertTrue(completed.output().contains("[REDACTED]"));
    }

    @Test
    void mapsRuntimeTimeoutToABlockingIndeterminateResult() throws Exception {
        runtime.failure = new SandboxRuntime.RuntimeTimeoutException("profile deadline exceeded");

        ExecutionView submitted = jobs.submit(Operation.RUN_TESTS,
                request("workflow", "stable-key-for-timeout", patchDigest));
        ExecutionView completed = await(submitted.executionId());

        assertEquals(ExecutionStatus.TIMED_OUT, completed.status());
        assertEquals(Verdict.INDETERMINATE, completed.verdict());
        assertNull(completed.exitCode());
        assertEquals("sandbox profile timed out", completed.error());
    }

    @Test
    void paginatesOnlyRetainedRedactedOutputWithBoundedCursors() throws Exception {
        runtime.result = new SandboxRuntime.RuntimeResult(0,
                "API_TOKEN=top-secret\n" + "x".repeat(20_000), true);

        ExecutionView submitted = jobs.submit(Operation.RUN_TESTS,
                request("workflow", "paginated-output-key", patchDigest));
        ExecutionView first = await(submitted.executionId());

        assertTrue(first.outputTruncated());
        assertEquals(0, first.outputCursor());
        assertEquals(4_096, first.output().length());
        assertEquals(4_096, first.nextOutputCursor());
        assertFalse(first.output().contains("top-secret"));

        StringBuilder complete = new StringBuilder(first.output());
        Integer cursor = first.nextOutputCursor();
        while (cursor != null) {
            ExecutionView page = jobs.get("1", "task-1", commit, "workflow", TRACE_ID,
                    submitted.executionId(), cursor, 5_000);
            complete.append(page.output());
            cursor = page.nextOutputCursor();
        }
        assertEquals(first.outputTotalChars(), complete.length());
        assertTrue(complete.toString().startsWith("API_TOKEN=[REDACTED]"));
        assertThrows(IllegalArgumentException.class, () -> jobs.get(
                "1", "task-1", commit, "workflow", TRACE_ID, submitted.executionId(), -1, 10));
        assertThrows(IllegalArgumentException.class, () -> jobs.get(
                "1", "task-1", commit, "workflow", TRACE_ID, submitted.executionId(), 0, 16_385));
    }

    @Test
    void persistsHeartbeatForActiveJobsWithoutExpiringThem() throws Exception {
        jobs.shutdown();
        MutableClock mutableClock = new MutableClock(Instant.parse("2026-08-31T08:00:00Z"));
        clock = mutableClock;
        runtime = new FakeRuntime();
        runtime.block = true;
        jobs = service(runtime);

        ExecutionView submitted = jobs.submit(Operation.RUN_TESTS,
                request("workflow", "heartbeat-running-key", patchDigest));
        assertTrue(runtime.started.await(2, TimeUnit.SECONDS));
        ExecutionView running = jobs.get(
                "1", "task-1", commit, "workflow", TRACE_ID, submitted.executionId());
        Instant initialHeartbeat = running.heartbeatAt();

        mutableClock.advance(Duration.ofSeconds(16));
        jobs.maintainJobs();
        ExecutionView refreshed = jobs.get(
                "1", "task-1", commit, "workflow", TRACE_ID, submitted.executionId());

        assertEquals(initialHeartbeat.plusSeconds(16), refreshed.heartbeatAt());
        assertEquals(refreshed.heartbeatAt(), store.load().stream()
                .filter(snapshot -> snapshot.executionId().equals(submitted.executionId()))
                .findFirst().orElseThrow().heartbeatAt());
        jobs.cancel("1", "task-1", commit, "workflow", TRACE_ID, submitted.executionId());
    }

    @Test
    void restoresCompletedJobsAndIdempotencyAfterRestart() throws Exception {
        runtime.result = new SandboxRuntime.RuntimeResult(0, "x".repeat(5_000), true);
        StartExecutionRequest request = request("workflow", "stable-key-across-restart", patchDigest);
        ExecutionView submitted = jobs.submit(Operation.VALIDATE_PATCH, request);
        ExecutionView completed = await(submitted.executionId());
        assertEquals(ExecutionStatus.SUCCEEDED, completed.status());
        jobs.shutdown();

        FakeRuntime restartedRuntime = new FakeRuntime();
        jobs = service(restartedRuntime);

        ExecutionView restored = jobs.get("1", "task-1", commit, "workflow", TRACE_ID, submitted.executionId());
        ExecutionView replayed = jobs.submit(Operation.VALIDATE_PATCH, request);
        assertEquals(ExecutionStatus.SUCCEEDED, restored.status());
        assertTrue(restored.outputTruncated());
        assertEquals(5_000, restored.outputTotalChars());
        assertEquals(4_096, restored.nextOutputCursor());
        assertEquals(restored.completedAt(), restored.heartbeatAt());
        assertEquals(submitted.executionId(), replayed.executionId());
        assertEquals(0, restartedRuntime.calls.get());
        assertEquals(1, restartedRuntime.reconciliations.get());
    }

    @Test
    void failsPersistedRunningJobsClosedAfterRestart() {
        jobs.shutdown();
        String executionId = "a".repeat(32);
        String idempotencyKey = "running-before-restart";
        Instant createdAt = Instant.now(clock).minus(Duration.ofHours(2));
        store.save(JobSnapshot.versionOne(executionId, "task-1", commit, patchDigest, idempotencyKey,
                Operation.RUN_TESTS, ExecutionStatus.RUNNING, Verdict.PENDING, null, null, null,
                createdAt, createdAt.plusSeconds(1), null));

        FakeRuntime restartedRuntime = new FakeRuntime();
        jobs = service(restartedRuntime);

        ExecutionView restored = jobs.get("1", "task-1", commit, "workflow", TRACE_ID, executionId);
        assertEquals(ExecutionStatus.FAILED, restored.status());
        assertEquals(Verdict.INDETERMINATE, restored.verdict());
        assertEquals("sandbox controller restarted during execution", restored.error());
        assertNotNull(restored.completedAt());
        assertEquals(restored.completedAt(), restored.heartbeatAt());
        assertTrue(Files.exists(properties.stateRoot().resolve(executionId + ".json")));
        assertEquals(1, restartedRuntime.reconciliations.get());
    }

    @Test
    void expiresCompletedJobsAndAllowsIdempotentResubmissionAfterRestart() throws Exception {
        jobs.shutdown();
        Instant firstRun = Instant.parse("2026-08-31T08:00:00Z");
        clock = Clock.fixed(firstRun, ZoneOffset.UTC);
        runtime = new FakeRuntime();
        jobs = service(runtime);
        StartExecutionRequest request = request("workflow", "reusable-key-after-expiry", patchDigest);

        ExecutionView submitted = jobs.submit(Operation.VALIDATE_PATCH, request);
        ExecutionView completed = await(submitted.executionId());
        Path snapshot = properties.stateRoot().resolve(submitted.executionId() + ".json");
        assertEquals(ExecutionStatus.SUCCEEDED, completed.status());
        assertTrue(Files.exists(snapshot));
        jobs.shutdown();

        clock = Clock.fixed(firstRun.plus(Duration.ofHours(2)), ZoneOffset.UTC);
        FakeRuntime restartedRuntime = new FakeRuntime();
        jobs = service(restartedRuntime);

        assertThrows(IllegalArgumentException.class, () -> jobs.get(
                "1", "task-1", commit, "workflow", TRACE_ID, submitted.executionId()));
        assertFalse(Files.exists(snapshot));

        ExecutionView replayed = jobs.submit(Operation.VALIDATE_PATCH, request);
        ExecutionView replayedCompleted = await(replayed.executionId());
        assertNotEquals(submitted.executionId(), replayed.executionId());
        assertEquals(ExecutionStatus.SUCCEEDED, replayedCompleted.status());
        assertEquals(1, restartedRuntime.calls.get());
    }

    @Test
    void rejectsUnauthorizedActorsDigestMismatchAndCrossTaskHandles() {
        assertThrows(SecurityException.class, () -> jobs.submit(Operation.VALIDATE_PATCH,
                request("developer", "unauthorized-key-value", patchDigest)));
        assertThrows(IllegalArgumentException.class, () -> jobs.submit(Operation.VALIDATE_PATCH,
                request("workflow", "wrong-digest-key-value", "0".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> jobs.submit(Operation.RUN_TESTS,
                new StartExecutionRequest("1", "task;docker-run", commit, "workflow", TRACE_ID,
                        "injected-task-key-value", patchDigest)));

        ExecutionView submitted = assertDoesNotThrow(() -> jobs.submit(Operation.RUN_TESTS,
                request("workflow", "cross-task-test-key", patchDigest)));
        assertThrows(IllegalArgumentException.class, () -> jobs.get(
                "1", "task-2", commit, "workflow", TRACE_ID, submitted.executionId()));
    }

    @Test
    void cancelsARunningJobAndInvokesRuntimeCleanup() throws Exception {
        runtime.block = true;
        ExecutionView submitted = jobs.submit(Operation.RUN_TESTS,
                request("workflow", "cancellable-test-key", patchDigest));
        assertTrue(runtime.started.await(2, TimeUnit.SECONDS));

        ExecutionView cancelled = jobs.cancel(
                "1", "task-1", commit, "workflow", TRACE_ID, submitted.executionId());

        assertEquals(ExecutionStatus.CANCELLED, cancelled.status());
        assertEquals(Verdict.INDETERMINATE, cancelled.verdict());
        assertEquals(submitted.executionId(), runtime.cancelledExecutionId);
    }

    private SandboxJobService service(SandboxRuntime sandboxRuntime) {
        return new SandboxJobService(properties, sandboxRuntime, new SimpleMeterRegistry(), store, clock);
    }

    private StartExecutionRequest request(String actor, String key, String digest) {
        return new StartExecutionRequest("1", "task-1", commit, actor, TRACE_ID, key, digest);
    }

    private ExecutionView await(String executionId) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            ExecutionView view = jobs.get("1", "task-1", commit, "workflow", TRACE_ID, executionId);
            if (view.status() != ExecutionStatus.ACCEPTED && view.status() != ExecutionStatus.RUNNING) {
                return view;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("job did not complete");
    }

    private static void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.waitFor(), output);
    }

    private static String output(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes()).strip();
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private static final class FakeRuntime implements SandboxRuntime {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger reconciliations = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private volatile RuntimeResult result = new RuntimeResult(0, "ok");
        private volatile Exception failure;
        private volatile boolean block;
        private volatile String cancelledExecutionId;
        private volatile Operation lastOperation;

        @Override
        public RuntimeResult execute(Operation operation, String executionId, Path workspace) throws Exception {
            calls.incrementAndGet();
            lastOperation = operation;
            started.countDown();
            if (failure != null) {
                throw failure;
            }
            if (block) {
                new CountDownLatch(1).await();
            }
            return result;
        }

        @Override
        public void cancel(String executionId) {
            cancelledExecutionId = executionId;
        }

        @Override
        public void reconcileOrphans() {
            reconciliations.incrementAndGet();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        private void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
