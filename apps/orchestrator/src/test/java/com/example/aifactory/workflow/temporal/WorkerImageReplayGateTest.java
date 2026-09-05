package com.example.aifactory.workflow.temporal;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerImageReplayGateTest {
    @Test
    void workerImagePackagingCanOnlyRunAfterTheVersionedHistoryReplayGate() throws Exception {
        Path dockerfile = List.of(Path.of("Dockerfile"), Path.of("apps/orchestrator/Dockerfile")).stream()
                .filter(Files::isRegularFile).findFirst().orElseThrow();
        String source = Files.readString(dockerfile);
        int replay = source.indexOf("mvn test -Dtest=WorkflowDeterminismArchitectureTest");
        int packageWorker = source.indexOf("mvn package -DskipTests", replay);

        assertThat(replay).isGreaterThanOrEqualTo(0);
        assertThat(packageWorker).as("the worker artifact must not be packaged before replay")
                .isGreaterThan(replay);
        assertThat(source.substring(replay, packageWorker)).contains("; \\");
    }
}
