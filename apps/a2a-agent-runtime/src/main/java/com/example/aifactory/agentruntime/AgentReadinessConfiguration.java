package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.RoleScopedAgentContext;
import io.temporal.api.workflowservice.v1.GetSystemInfoRequest;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.worker.Worker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
class AgentReadinessConfiguration {
    @Bean(name = "agentRuntimeDependencies")
    AgentRuntimeReadinessHealthIndicator agentRuntimeDependencies(
            AgentCardController cards, A2aAgentCardSigner signer, A2aTaskStore store,
            A2aTaskStoreProperties taskStore,
            AgentTemporalProperties temporal, ObjectProvider<WorkflowServiceStubs> temporalService,
            ObjectProvider<Worker> temporalWorker, LlmAdapterProperties llm, AgentMcpProperties mcp,
            RoleScopedAgentContext role, org.springframework.web.reactive.function.client.WebClient.Builder webClient,
            tools.jackson.databind.ObjectMapper mapper) {
        McpSdkSessionFactory sessions = new McpSdkSessionFactory(webClient, mapper);
        List<AgentRuntimeReadinessHealthIndicator.ReadinessCheck> checks = new ArrayList<>();
        checks.add(check("agentCard", () -> {
            Map<String, Object> card = cards.publicCard();
            if (!signer.verify(card)) throw new IllegalStateException("Invalid local Agent Card signature");
            @SuppressWarnings("unchecked") Map<String, Object> metadata = (Map<String, Object>) card.get("metadata");
            if (metadata == null || !Instant.parse(String.valueOf(metadata.get("expiresAt"))).isAfter(Instant.now())) {
                throw new IllegalStateException("Local Agent Card is expired");
            }
        }));
        checks.add(check("taskStore", () -> {
            if (!taskStore.enabled()) throw new IllegalStateException("Durable A2A task store is disabled");
            store.checkHealth();
        }));
        checks.add(check("temporal", () -> {
            if (!temporal.enabled()) throw new IllegalStateException("Temporal is disabled");
            temporalService.getObject().blockingStub().withDeadlineAfter(3, java.util.concurrent.TimeUnit.SECONDS)
                    .getSystemInfo(GetSystemInfoRequest.getDefaultInstance());
        }));
        checks.add(check("taskQueue", () -> {
            Worker worker = temporalWorker.getObject();
            if (worker.isSuspended() || !temporal.taskQueue(role.identity().role()).equals(worker.getTaskQueue())) {
                throw new IllegalStateException("Temporal task queue is unavailable");
            }
        }));
        checks.add(check("llm", () -> probeLlm(llm)));
        checks.add(check("mcp", () -> probeMcp(role, mcp, sessions)));
        return new AgentRuntimeReadinessHealthIndicator(checks);
    }

    private static AgentRuntimeReadinessHealthIndicator.ReadinessCheck check(String name, Runnable probe) {
        return new AgentRuntimeReadinessHealthIndicator.ReadinessCheck(name, probe);
    }

    private static void probeLlm(LlmAdapterProperties properties) {
        try {
            String base = properties.baseUrl().replaceFirst("/+$", "");
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/models"))
                    .timeout(Duration.ofSeconds(3)).GET();
            if (properties.apiKey() != null && !properties.apiKey().isBlank()) {
                request.header("Authorization", "Bearer " + properties.apiKey());
            }
            int status = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("LLM readiness probe failed");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LLM readiness probe interrupted", interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("LLM readiness probe failed", failure);
        }
    }

    private static void probeMcp(RoleScopedAgentContext role, AgentMcpProperties properties,
                                 McpSdkSessionFactory sessions) {
        if (!properties.enabled()) throw new IllegalStateException("MCP is disabled");
        Map<String, URI> endpoints = Map.of(
                "context", properties.repositoryContextUrl(), "evidence", properties.evidenceUrl());
        for (Map.Entry<String, URI> endpoint : endpoints.entrySet()) {
            List<String> required = role.allowedTools().stream()
                    .filter(tool -> tool.startsWith(endpoint.getKey() + ".")).toList();
            if (required.isEmpty() && !"evidence".equals(endpoint.getKey())) continue;
            String server = "context".equals(endpoint.getKey()) ? "repository-context-mcp" : "evidence-mcp";
            try (RoleScopedMcpClient.Session session = sessions.connect(
                    server, endpoint.getValue(), properties.requestTimeout())) {
                if (!session.tools().containsAll(required)
                        || ("evidence".equals(endpoint.getKey()) && !session.tools().contains("evidence.store"))) {
                    throw new IllegalStateException("Required MCP tools are unavailable");
                }
            }
        }
    }
}
