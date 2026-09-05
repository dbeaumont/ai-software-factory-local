package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.config.GkeControllerProperties;
import com.example.aifactory.sandbox.service.GkeJobController.JobRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Kubernetes API implementation used in GKE. Requests are derived only from registered sandbox profiles. */
@Service
@ConditionalOnProperty(prefix = "ai-factory.sandbox", name = "runtime", havingValue = "gke")
public class KubernetesGkeJobController implements GkeJobController {
    private static final String LABEL_SELECTOR = "app.kubernetes.io/name=ai-factory-sandbox-job";
    private static final int MAX_API_ATTEMPTS = 3;
    private static final int MAX_LOG_CHARS = 65_536;
    private final GkeControllerProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;

    public KubernetesGkeJobController(GkeControllerProperties properties, ObjectMapper mapper) throws Exception {
        this(properties, mapper, clusterClient(properties));
    }

    KubernetesGkeJobController(GkeControllerProperties properties, ObjectMapper mapper, HttpClient client) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public SandboxRuntime.RuntimeResult run(JobRequest request) throws Exception {
        String name = jobName(request.executionId());
        HttpResponse<byte[]> created = send("POST", jobsUri(), mapper.writeValueAsBytes(job(request, name)));
        if (created.statusCode() != 201 && created.statusCode() != 409) {
            throw failure("create", created);
        }
        Instant deadline = Instant.now().plus(request.timeout()).plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<byte[]> response = send("GET", jobUri(name), null);
            if (response.statusCode() != 200) {
                throw failure("read", response);
            }
            JsonNode status = mapper.readTree(response.body()).path("status");
            if (status.path("succeeded").asInt() > 0) {
                LogOutput logs = boundedLogs(name);
                return new SandboxRuntime.RuntimeResult(0, logs.value(), logs.truncated());
            }
            if (status.path("failed").asInt() > 0) {
                LogOutput logs = boundedLogs(name);
                return new SandboxRuntime.RuntimeResult(1, logs.value(), logs.truncated());
            }
            Thread.sleep(properties.pollInterval());
        }
        LogOutput logs = boundedLogs(name);
        cancel(request.executionId());
        throw new SandboxRuntime.RuntimeTimeoutException("GKE sandbox job timed out", logs.value(), logs.truncated());
    }

    @Override
    public void cancel(String executionId) {
        try {
            HttpResponse<byte[]> response = send("DELETE", jobUri(jobName(executionId)),
                    mapper.writeValueAsBytes(Map.of(
                            "apiVersion", "v1", "kind", "DeleteOptions",
                            "propagationPolicy", "Background", "gracePeriodSeconds", 0)));
            if (response.statusCode() != 200 && response.statusCode() != 202 && response.statusCode() != 404) {
                throw failure("delete", response);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("failed to cancel GKE sandbox job", exception);
        }
    }

    @Override
    public void reconcileOrphans() throws Exception {
        URI uri = URI.create(jobsUri() + "?labelSelector="
                + URLEncoder.encode(LABEL_SELECTOR, StandardCharsets.UTF_8));
        HttpResponse<byte[]> response = send("GET", uri, null);
        if (response.statusCode() != 200) {
            throw failure("list", response);
        }
        for (JsonNode item : mapper.readTree(response.body()).path("items")) {
            JsonNode status = item.path("status");
            if (status.path("active").asInt() > 0) {
                cancel(item.path("metadata").path("labels").path("ai-factory.io/execution-id").asText());
            }
        }
    }

    Map<String, Object> job(JobRequest request, String name) {
        List<Map<String, Object>> environment = new ArrayList<>();
        for (String variable : request.environmentSecretNames()) {
            environment.add(Map.of("name", variable, "valueFrom", Map.of("secretKeyRef", Map.of(
                    "name", properties.environmentSecret(), "key", variable, "optional", true))));
        }
        environment.add(Map.of("name", "HOME", "value", "/tmp/home"));
        List<Map<String, Object>> volumeMounts = new ArrayList<>();
        volumeMounts.add(Map.of("name", "workspace", "mountPath", "/workspace",
                "subPath", request.taskDirectory(), "readOnly", request.workspaceReadOnly()));
        volumeMounts.add(Map.of("name", "tmp", "mountPath", "/tmp"));
        List<Map<String, Object>> volumes = new ArrayList<>();
        volumes.add(Map.of("name", "workspace",
                "persistentVolumeClaim", Map.of("claimName", properties.workspaceClaim())));
        volumes.add(Map.of("name", "tmp", "emptyDir", Map.of("sizeLimit", "64Mi")));
        if (request.mavenCache()) {
            volumeMounts.add(Map.of("name", "maven-cache", "mountPath", "/tmp/home/.m2"));
            volumes.add(Map.of("name", "maven-cache", "emptyDir", Map.of("sizeLimit", "2Gi")));
        }
        if ("security-syft-trivy-v2".equals(request.profileId())) {
            volumeMounts.add(Map.of("name", "trivy-cache", "mountPath", "/tmp/home/.cache/trivy"));
            volumes.add(Map.of("name", "trivy-cache", "emptyDir", Map.of("sizeLimit", "1Gi")));
        }

        Map<String, Object> labels = Map.of(
                "app.kubernetes.io/name", "ai-factory-sandbox-job",
                "ai-factory.io/execution-id", request.executionId(),
                "ai-factory.io/network-policy", request.networkPolicy());
        Map<String, Object> container = new LinkedHashMap<>();
        container.put("name", "sandbox");
        container.put("image", request.image());
        container.put("imagePullPolicy", "IfNotPresent");
        container.put("command", List.of("python3", "/opt/ai-factory/runner.py", "--execute-profile",
                request.profileId(), Long.toString(request.timeout().toSeconds())));
        container.put("workingDir", "/workspace");
        container.put("env", environment);
        container.put("resources", Map.of(
                "requests", Map.of("cpu", request.cpu(), "memory", request.memory()),
                "limits", Map.of("cpu", request.cpu(), "memory", request.memory())));
        container.put("securityContext", Map.of(
                "allowPrivilegeEscalation", false,
                "readOnlyRootFilesystem", true,
                "runAsNonRoot", true,
                "runAsUser", 10000,
                "capabilities", Map.of("drop", List.of("ALL"))));
        container.put("volumeMounts", volumeMounts);

        Map<String, Object> podSpec = new LinkedHashMap<>();
        podSpec.put("serviceAccountName", properties.jobServiceAccount());
        podSpec.put("automountServiceAccountToken", false);
        podSpec.put("runtimeClassName", properties.runtimeClassName());
        podSpec.put("restartPolicy", "Never");
        podSpec.put("securityContext", Map.of("seccompProfile", Map.of("type", "RuntimeDefault")));
        podSpec.put("containers", List.of(container));
        podSpec.put("volumes", volumes);

        return Map.of(
                "apiVersion", "batch/v1",
                "kind", "Job",
                "metadata", Map.of("name", name, "namespace", properties.namespace(), "labels", labels,
                        "annotations", Map.of("ai-factory.io/profile-id", request.profileId())),
                "spec", Map.of(
                        "backoffLimit", 0,
                        "activeDeadlineSeconds", request.timeout().toSeconds(),
                        "ttlSecondsAfterFinished", properties.ttlSecondsAfterFinished(),
                        "template", Map.of("metadata", Map.of("labels", labels), "spec", podSpec)));
    }

    private LogOutput boundedLogs(String jobName) throws Exception {
        URI pods = URI.create(api("/api/v1/namespaces/" + properties.namespace() + "/pods")
                + "?labelSelector=" + URLEncoder.encode("job-name=" + jobName, StandardCharsets.UTF_8));
        HttpResponse<byte[]> listed = send("GET", pods, null);
        if (listed.statusCode() != 200) {
            return new LogOutput("", false);
        }
        JsonNode items = mapper.readTree(listed.body()).path("items");
        if (items.isEmpty()) {
            return new LogOutput("", false);
        }
        String podName = items.get(0).path("metadata").path("name").asText();
        HttpResponse<byte[]> logs = send("GET", URI.create(api("/api/v1/namespaces/" + properties.namespace()
                + "/pods/" + podName + "/log?container=sandbox")), null);
        if (logs.statusCode() != 200) {
            return new LogOutput("", false);
        }
        return bounded(new String(logs.body(), StandardCharsets.UTF_8));
    }

    private HttpResponse<byte[]> send(String method, URI uri, byte[] body) throws Exception {
        String token = Files.readString(properties.tokenFile(), StandardCharsets.UTF_8).strip();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        }
        HttpRequest built = request.build();
        for (int attempt = 1; attempt <= MAX_API_ATTEMPTS; attempt++) {
            try {
                HttpResponse<byte[]> response = client.send(built, HttpResponse.BodyHandlers.ofByteArray());
                if (!transientStatus(response.statusCode()) || attempt == MAX_API_ATTEMPTS) {
                    return response;
                }
            } catch (IOException exception) {
                if (attempt == MAX_API_ATTEMPTS) {
                    throw exception;
                }
            }
            Thread.sleep(Duration.ofMillis(200L << (attempt - 1)));
        }
        throw new IllegalStateException("unreachable Kubernetes API retry state");
    }

    static boolean transientStatus(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502
                || statusCode == 503 || statusCode == 504;
    }

    static LogOutput bounded(String output) {
        boolean truncated = output.length() > MAX_LOG_CHARS;
        return new LogOutput(truncated ? output.substring(output.length() - MAX_LOG_CHARS) : output, truncated);
    }

    private URI jobsUri() {
        return URI.create(api("/apis/batch/v1/namespaces/" + properties.namespace() + "/jobs"));
    }

    private URI jobUri(String name) {
        return URI.create(jobsUri() + "/" + name);
    }

    private String api(String path) {
        return properties.apiServer().toString().replaceAll("/$", "") + path;
    }

    static String jobName(String executionId) {
        if (executionId == null || !executionId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("invalid execution id");
        }
        return "ai-factory-sbx-" + executionId;
    }

    private static IllegalStateException failure(String operation, HttpResponse<byte[]> response) {
        return new IllegalStateException("Kubernetes " + operation + " failed with HTTP " + response.statusCode());
    }

    record LogOutput(String value, boolean truncated) {
    }

    private static HttpClient clusterClient(GkeControllerProperties properties) throws Exception {
        CertificateFactory certificates = CertificateFactory.getInstance("X.509");
        X509Certificate certificate;
        try (InputStream input = Files.newInputStream(properties.caCertificateFile())) {
            certificate = (X509Certificate) certificates.generateCertificate(input);
        }
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("kubernetes", certificate);
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, trustManagers.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5)).build();
    }
}
