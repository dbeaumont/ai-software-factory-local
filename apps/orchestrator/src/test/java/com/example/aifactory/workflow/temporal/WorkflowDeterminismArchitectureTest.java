package com.example.aifactory.workflow.temporal;

import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestEnvironmentOptions;
import org.junit.jupiter.api.Test;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.WorkerFactoryOptions;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import com.example.aifactory.service.ExecutionTracer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowDeterminismArchitectureTest {
    private static final Path WORKFLOW_SOURCES = Path.of(
            "src/main/java/com/example/aifactory/workflow/temporal");

    private static final Map<String, String> FORBIDDEN = forbiddenPatterns();

    @Test
    void workflowImplementationsContainNoDirectNondeterministicEffect() throws IOException {
        List<Path> implementations;
        try (var files = Files.list(WORKFLOW_SOURCES)) {
            implementations = files
                    .filter(path -> path.getFileName().toString().endsWith("WorkflowImpl.java"))
                    .sorted()
                    .toList();
        }

        assertThat(implementations).isNotEmpty();
        for (Path implementation : implementations) {
            String source = Files.readString(implementation);
            FORBIDDEN.forEach((pattern, rationale) -> assertThat(source)
                    .as("%s must not contain %s (%s)", implementation, pattern, rationale)
                    .doesNotContain(pattern));
        }
    }

    @Test
    void completedRootHistoryReplaysWithoutANondeterminismFailure() throws Exception {
        String workflowId = TemporalIds.workflow("determinism-task", "attempt-1");
        io.temporal.common.WorkflowExecutionHistory history;
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("determinism-test");
            worker.registerWorkflowImplementationTypes(
                    SoftwareFactoryWorkflowImpl.class, DelegationWorkflowImpl.class,
                    IndependentReviewWorkflowImpl.class);
            environment.start();
            SoftwareFactoryWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryWorkflow.class, WorkflowOptions.newBuilder()
                            .setWorkflowId(workflowId).setTaskQueue("determinism-test").build());

            workflow.run(new SoftwareFactoryWorkflow.Request(
                    "determinism-task", "attempt-1", "a".repeat(40), "deterministic change"));
            history = environment.getWorkflowClient().fetchHistory(workflowId);
        }

        WorkflowReplayer.replayWorkflowExecution(history, SoftwareFactoryWorkflowImpl.class);
    }

    @Test
    void completedHistoryReplayEmitsNoDuplicateWorkflowSpan() throws Exception {
        String workflowId = TemporalIds.workflow("replay-telemetry-task", "attempt-1");
        io.temporal.common.WorkflowExecutionHistory history;
        try (TestWorkflowEnvironment environment = TestWorkflowEnvironment.newInstance()) {
            var worker = environment.newWorker("replay-history-source");
            worker.registerWorkflowImplementationTypes(SoftwareFactoryWorkflowImpl.class);
            environment.start();
            SoftwareFactoryWorkflow workflow = environment.getWorkflowClient().newWorkflowStub(
                    SoftwareFactoryWorkflow.class, WorkflowOptions.newBuilder()
                            .setWorkflowId(workflowId).setTaskQueue("replay-history-source").build());
            workflow.run(new SoftwareFactoryWorkflow.Request(
                    "replay-telemetry-task", "attempt-1", "b".repeat(40), "replay-safe telemetry"));
            history = environment.getWorkflowClient().fetchHistory(workflowId);
        }

        AtomicInteger stoppedSpans = new AtomicInteger();
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override public void onStop(Observation.Context context) { stoppedSpans.incrementAndGet(); }
            @Override public boolean supportsContext(Observation.Context context) { return true; }
        });
        WorkerFactoryOptions workerFactoryOptions = WorkerFactoryOptions.newBuilder()
                .setWorkerInterceptors(new TemporalWorkerTracingInterceptor(new ExecutionTracer(registry)))
                .build();
        TestEnvironmentOptions replayOptions = TestEnvironmentOptions.newBuilder()
                .setWorkerFactoryOptions(workerFactoryOptions)
                .build();
        try (TestWorkflowEnvironment replayEnvironment = TestWorkflowEnvironment.newInstance(replayOptions)) {
            WorkflowReplayer.replayWorkflowExecution(
                    history, replayEnvironment, SoftwareFactoryWorkflowImpl.class);
        }

        assertThat(stoppedSpans).hasValue(0);
    }

    private static Map<String, String> forbiddenPatterns() {
        Map<String, String> patterns = new LinkedHashMap<>();
        patterns.put("java.io.", "direct filesystem or stream I/O");
        patterns.put("java.nio.file.", "direct filesystem I/O");
        patterns.put("java.net.", "direct network access");
        patterns.put("java.net.http.", "direct HTTP access");
        patterns.put("WebClient", "direct HTTP client access");
        patterns.put("McpToolInvoker", "MCP effects belong in Activities");
        patterns.put("LlmGatewayClient", "LLM effects belong in Activities");
        patterns.put("EvidenceRepository", "evidence persistence belongs in Activities");
        patterns.put("System.currentTimeMillis(", "system clock access");
        patterns.put("System.nanoTime(", "system clock access");
        patterns.put("Instant.now(", "system clock access");
        patterns.put("LocalDateTime.now(", "system clock access");
        patterns.put("OffsetDateTime.now(", "system clock access");
        patterns.put("ZonedDateTime.now(", "system clock access");
        patterns.put("UUID.randomUUID(", "direct randomness");
        patterns.put("new Random(", "direct randomness");
        patterns.put("ThreadLocalRandom", "direct randomness");
        patterns.put("SecureRandom", "direct randomness");
        patterns.put("ProcessBuilder", "direct process execution");
        patterns.put("Runtime.getRuntime(", "direct process/runtime access");
        return Map.copyOf(patterns);
    }
}
