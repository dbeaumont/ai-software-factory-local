package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.config.SandboxExecutionProperties;
import com.example.aifactory.sandbox.model.SandboxModels.Operation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GkeSandboxRuntimeTest {
    @Test
    void mapsRegisteredProfileToBoundedControllerRequest() throws Exception {
        AtomicReference<GkeJobController.JobRequest> captured = new AtomicReference<>();
        GkeJobController controller = new GkeJobController() {
            @Override
            public SandboxRuntime.RuntimeResult run(JobRequest request) {
                captured.set(request);
                return new SandboxRuntime.RuntimeResult(0, "passed");
            }

            @Override
            public void cancel(String executionId) {
            }

            @Override
            public void reconcileOrphans() {
            }
        };
        GkeSandboxRuntime runtime = new GkeSandboxRuntime(properties(), controller);

        SandboxRuntime.RuntimeResult result = runtime.execute(Operation.RUN_SECURITY, "a".repeat(32),
                Path.of("/workspace/tasks/task-1"));

        GkeJobController.JobRequest request = captured.get();
        assertEquals(0, result.exitCode());
        assertEquals("security-syft-trivy-v2", request.profileId());
        assertEquals("sandbox-dependency-egress", request.networkPolicy());
        assertEquals("2Gi", request.memory());
        assertEquals(512, request.pidsLimit());
        assertEquals("task-1", request.taskDirectory());
        assertTrue(request.environmentSecretNames().contains("HTTP_PROXY"));
    }

    @Test
    void forwardsKnownActiveExecutionsDuringPeriodicReconciliation() throws Exception {
        AtomicReference<Set<String>> retained = new AtomicReference<>();
        GkeJobController controller = new GkeJobController() {
            @Override
            public SandboxRuntime.RuntimeResult run(JobRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void cancel(String executionId) {
            }

            @Override
            public void reconcileOrphans() {
            }

            @Override
            public void reconcileOrphans(Set<String> retainedExecutionIds) {
                retained.set(Set.copyOf(retainedExecutionIds));
            }
        };
        GkeSandboxRuntime runtime = new GkeSandboxRuntime(properties(), controller);
        Set<String> active = Set.of("a".repeat(32));

        runtime.reconcileOrphans(active);

        assertEquals(active, retained.get());
    }

    private static SandboxExecutionProperties properties() {
        return new SandboxExecutionProperties(Path.of("/workspace/tasks"), Path.of("/state"), "workspace",
                "sha256:" + "a".repeat(64), "factory", 2, 32, 2, 500, Duration.ofDays(7),
                Duration.ofSeconds(15), 65_536, 1_048_576, "", "", "http://sonarqube:9000", "");
    }
}
