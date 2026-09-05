package com.example.aifactory.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GkeObservabilityConfigurationTest {

    @Test
    void hardensAndScalesTheGoogleCloudGateway() throws Exception {
        Path root = repositoryRoot();
        String workload = Files.readString(root.resolve("infrastructure/gke/observability/otel-collector.yaml"));
        String config = Files.readString(root.resolve("infrastructure/gke/observability/otel-collector-config.yaml"));

        assertThat(workload)
                .contains("replicas: 2", "kind: PodDisruptionBudget", "kind: HorizontalPodAutoscaler")
                .contains("kind: ResourceQuota", "kind: LimitRange", "topologySpreadConstraints:")
                .contains("iam.gke.io/gcp-service-account", "readOnlyRootFilesystem: true")
                .contains("allowPrivilegeEscalation: false", "capabilities: { drop: [ALL] }")
                .contains("kind: NetworkPolicy", "type: ClusterIP")
                .doesNotContain("type: LoadBalancer", "hostPort:", "docker.sock");
        assertThat(config)
                .contains("client_ca_file:", "tail_sampling:", "googlecloud:")
                .contains("otlp/self:", "endpoint: 127.0.0.1:14318")
                .contains("transform/redact:", "memory_limiter:", "sending_queue:")
                .doesNotContain("service_account:", "credentials:", "debug:");
    }

    private static Path repositoryRoot() {
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("infrastructure/compose.yaml"))) return cwd;
        return cwd.resolve("../..").normalize();
    }
}
