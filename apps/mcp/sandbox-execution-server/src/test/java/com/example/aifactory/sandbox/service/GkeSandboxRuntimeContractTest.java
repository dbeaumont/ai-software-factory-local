package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.config.SandboxExecutionProperties;

import java.nio.file.Path;
import java.time.Duration;

class GkeSandboxRuntimeContractTest extends SandboxRuntimeContract {
    @Override
    protected SandboxRuntime successfulRuntime() {
        return runtime(false);
    }

    @Override
    protected SandboxRuntime timedOutRuntime() {
        return runtime(true);
    }

    private static SandboxRuntime runtime(boolean timeout) {
        GkeJobController controller = new GkeJobController() {
            @Override
            public SandboxRuntime.RuntimeResult run(JobRequest request) throws Exception {
                if (timeout) {
                    throw new SandboxRuntime.RuntimeTimeoutException(
                            "sandbox profile timed out", "partial output", true);
                }
                return new SandboxRuntime.RuntimeResult(7, "bounded output", true);
            }

            @Override
            public void cancel(String executionId) {
            }

            @Override
            public void reconcileOrphans() {
            }
        };
        return new GkeSandboxRuntime(properties(), controller);
    }

    private static SandboxExecutionProperties properties() {
        return new SandboxExecutionProperties(Path.of("/workspace/tasks"), Path.of("/state"), "workspace",
                "sha256:" + "d".repeat(64), "factory", 2, 32, 2, 500, Duration.ofDays(7),
                Duration.ofSeconds(15), 65_536, 1_048_576, "", "", "http://sonarqube:9000", "");
    }
}
