package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.config.GkeControllerProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KubernetesGkeJobControllerTest {
    @Test
    @SuppressWarnings("unchecked")
    void rendersARestrictedJobFromTheBoundedRequest() {
        KubernetesGkeJobController controller = new KubernetesGkeJobController(properties(),
                new ObjectMapper(), HttpClient.newHttpClient());
        GkeJobController.JobRequest request = new GkeJobController.JobRequest(
                "a".repeat(32), "RUN_SECURITY", "security-syft-trivy-v2", "task-1",
                "registry/sandbox@sha256:" + "b".repeat(64), "b".repeat(64),
                "sandbox-dependency-egress", "2", "2Gi", 512, Duration.ofMinutes(10),
                false, false, List.of("HTTP_PROXY"));

        Map<String, Object> job = controller.job(request, KubernetesGkeJobController.jobName(request.executionId()));
        Map<String, Object> spec = (Map<String, Object>) job.get("spec");
        Map<String, Object> template = (Map<String, Object>) spec.get("template");
        Map<String, Object> pod = (Map<String, Object>) template.get("spec");
        Map<String, Object> container = ((List<Map<String, Object>>) pod.get("containers")).getFirst();
        Map<String, Object> security = (Map<String, Object>) container.get("securityContext");

        assertEquals(0, spec.get("backoffLimit"));
        assertEquals("Never", pod.get("restartPolicy"));
        assertEquals(Boolean.FALSE, pod.get("automountServiceAccountToken"));
        assertEquals("gvisor", pod.get("runtimeClassName"));
        assertEquals(Boolean.FALSE, security.get("allowPrivilegeEscalation"));
        assertEquals(Boolean.TRUE, security.get("readOnlyRootFilesystem"));
        assertEquals(List.of("python3", "/opt/ai-factory/runner.py", "--execute-profile",
                "security-syft-trivy-v2", "600"), container.get("command"));
        assertFalse(container.toString().contains("docker.sock"));
    }

    @Test
    void rejectsInvalidExecutionIds() {
        assertThrows(IllegalArgumentException.class, () -> KubernetesGkeJobController.jobName("../../job"));
    }

    @Test
    void boundsLogsAndReportsTruncation() {
        String oversized = "x".repeat(65_537);

        KubernetesGkeJobController.LogOutput output = KubernetesGkeJobController.bounded(oversized);

        assertEquals(65_536, output.value().length());
        assertTrue(output.truncated());
        assertFalse(KubernetesGkeJobController.bounded("short").truncated());
    }

    @Test
    void retriesOnlyTemporaryKubernetesFailures() {
        assertTrue(KubernetesGkeJobController.transientStatus(429));
        assertTrue(KubernetesGkeJobController.transientStatus(503));
        assertFalse(KubernetesGkeJobController.transientStatus(400));
        assertFalse(KubernetesGkeJobController.transientStatus(409));
    }

    private static GkeControllerProperties properties() {
        return new GkeControllerProperties(URI.create("https://kubernetes.default.svc"),
                "ai-factory-sandbox", "sa-sandbox-controller", "sa-sandbox-job", "gvisor",
                "factory-workspace", "sandbox-profile-environment", Path.of("token"), Path.of("ca"),
                Duration.ofSeconds(1), 300);
    }
}
