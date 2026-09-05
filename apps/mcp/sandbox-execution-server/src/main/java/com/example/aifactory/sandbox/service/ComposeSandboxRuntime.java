package com.example.aifactory.sandbox.service;

import com.example.aifactory.sandbox.config.ComposeSandboxProperties;
import com.example.aifactory.sandbox.config.SandboxExecutionProperties;
import com.example.aifactory.sandbox.model.SandboxModels.Operation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(prefix = "ai-factory.sandbox", name = "runtime", havingValue = "compose", matchIfMissing = true)
public class ComposeSandboxRuntime implements SandboxRuntime {
    private final SandboxExecutionProperties properties;
    private final ComposeSandboxProperties compose;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final List<URI> runners;

    @Autowired
    public ComposeSandboxRuntime(SandboxExecutionProperties properties, ComposeSandboxProperties compose,
                                 ObjectMapper objectMapper) {
        this(properties, compose, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
    }

    ComposeSandboxRuntime(SandboxExecutionProperties properties, ComposeSandboxProperties compose,
                          ObjectMapper objectMapper, HttpClient httpClient) {
        this.properties = properties;
        this.compose = compose;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.runners = List.of(compose.readOnlyRunnerUrl(), compose.writeRunnerUrl(),
                compose.dependencyRunnerUrl(), compose.qualityRunnerUrl());
    }

    @Override
    public RuntimeResult execute(Operation operation, String executionId, Path workspace) throws Exception {
        SandboxProfiles.Profile profile = SandboxProfiles.forOperation(operation, workspace);
        URI runner = runner(profile);
        byte[] body = objectMapper.writeValueAsBytes(Map.of(
                "execution_id", executionId,
                "task_directory", workspace.getFileName().toString(),
                "profile_id", profile.id(),
                "image_digest", properties.imageDigest(),
                "timeout_seconds", profile.timeout().toSeconds(),
                "max_output_chars", properties.maxOutputChars()));
        HttpRequest request = HttpRequest.newBuilder(runner.resolve("/v1/executions"))
                .timeout(profile.timeout().plusSeconds(15))
                .header("Authorization", "Bearer " + compose.token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("compose sandbox runner rejected execution with HTTP "
                    + response.statusCode());
        }
        RunnerResponse result = objectMapper.readValue(response.body(), RunnerResponse.class);
        if (result.timedOut()) {
            throw new RuntimeTimeoutException("sandbox profile timed out", result.output(), result.outputTruncated());
        }
        return new RuntimeResult(result.exitCode(), result.output(), result.outputTruncated());
    }

    @Override
    public void cancel(String executionId) {
        for (URI runner : runners) {
            try {
                HttpRequest request = HttpRequest.newBuilder(runner.resolve("/v1/executions/" + executionId))
                        .timeout(Duration.ofSeconds(5))
                        .header("Authorization", "Bearer " + compose.token())
                        .DELETE().build();
                httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // Best effort across the fixed local runner pool.
            }
        }
    }

    @Override
    public void reconcileOrphans(Set<String> retainedExecutionIds) throws Exception {
        for (URI runner : runners) {
            HttpRequest request = HttpRequest.newBuilder(runner.resolve("/v1/executions"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + compose.token())
                    .GET().build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("compose sandbox orphan discovery failed with HTTP "
                        + response.statusCode());
            }
            ActiveExecutions active = objectMapper.readValue(response.body(), ActiveExecutions.class);
            for (String executionId : active.executionIds()) {
                if (!retainedExecutionIds.contains(executionId)) {
                    cancel(executionId);
                }
            }
        }
    }

    private URI runner(SandboxProfiles.Profile profile) {
        if (profile.workspaceReadOnly()) {
            return compose.readOnlyRunnerUrl();
        }
        return switch (profile.network()) {
            case "none" -> compose.writeRunnerUrl();
            case "quality" -> compose.qualityRunnerUrl();
            default -> compose.dependencyRunnerUrl();
        };
    }

    record RunnerResponse(int exitCode, String output, boolean outputTruncated, boolean timedOut) {
    }

    record ActiveExecutions(List<String> executionIds) {
        ActiveExecutions {
            executionIds = List.copyOf(executionIds);
        }
    }
}
