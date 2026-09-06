package com.example.aifactory.agentruntime;

import com.example.aifactory.agentcore.RoleScopedAgentContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvidenceArtifactPublisherTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void validatesStoresAndProjectsOnlyTheEvidenceReferenceIdempotently() throws Exception {
        JsonNode document = fixtures().path("patch-proposal-v1");
        byte[] content = mapper.writeValueAsBytes(document);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        InMemoryA2aTaskStore store = taskStore();
        AtomicReference<Map<String, Object>> request = new AtomicReference<>();
        RoleScopedMcpClient.SessionFactory sessions = (name, uri, timeout) -> new RoleScopedMcpClient.Session() {
            @Override public Set<String> tools() { return Set.of("evidence.store"); }
            @Override public String call(String tool, Map<String, Object> arguments) {
                request.set(arguments);
                return "{\"uri\":\"evidence://task-1/attempt-1/agent-result/" + digest
                        + "\",\"digest\":\"" + digest
                        + "\",\"status\":\"COMPLETE\",\"classification\":\"INTERNAL\"}";
            }
            @Override public void close() { }
        };
        EvidenceArtifactPublisher publisher = new EvidenceArtifactPublisher(
                RoleScopedAgentContext.load("developer", mapper), store,
                new AgentMcpProperties(true, Duration.ofSeconds(5), URI.create("http://context-mcp:8091"),
                        URI.create("http://evidence-mcp:8095")), sessions, mapper);
        AgentArtifactActivities.PublishCommand command = new AgentArtifactActivities.PublishCommand(
                "task-1", "attempt-1", "developer", "patch-proposal-v1", Set.of(),
                Base64.getEncoder().encodeToString(content), digest);

        AgentArtifactActivities.ArtifactReference first = publisher.publish(command);
        AgentArtifactActivities.ArtifactReference replay = publisher.publish(command);

        assertThat(first).isEqualTo(replay);
        assertThat(request.get()).containsEntry("actor", "developer").containsEntry("digest", digest);
        assertThat(store.artifacts("task-1", "tenant-a", "orchestrator")).singleElement()
                .satisfies(artifact -> assertThat(artifact.toString()).contains(first.uri(), digest));
    }

    @Test
    void rejectsContentWhoseDigestIsNotBound() throws Exception {
        EvidenceArtifactPublisher publisher = new EvidenceArtifactPublisher(
                RoleScopedAgentContext.load("developer", mapper), taskStore(),
                new AgentMcpProperties(true, Duration.ofSeconds(5), URI.create("http://context-mcp:8091"),
                        URI.create("http://evidence-mcp:8095")),
                (name, uri, timeout) -> { throw new AssertionError("MCP must not be called"); }, mapper);
        JsonNode document = fixtures().path("patch-proposal-v1");

        assertThatThrownBy(() -> publisher.publish(new AgentArtifactActivities.PublishCommand(
                "task-1", "attempt-1", "developer", "patch-proposal-v1", Set.of(),
                Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(document)), "0".repeat(64))))
                .isInstanceOf(SecurityException.class).hasMessageContaining("digest");
    }

    @Test
    void retriesEvidencePublicationAfterOutageAndKeepsOneImmutableArtifact() throws Exception {
        JsonNode document = fixtures().path("patch-proposal-v1");
        byte[] content = mapper.writeValueAsBytes(document);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        InMemoryA2aTaskStore store = taskStore();
        AtomicInteger calls = new AtomicInteger();
        EvidenceArtifactPublisher publisher = new EvidenceArtifactPublisher(
                RoleScopedAgentContext.load("developer", mapper), store,
                new AgentMcpProperties(true, Duration.ofSeconds(5), URI.create("http://context-mcp:8091"),
                        URI.create("http://evidence-mcp:8095")),
                (name, uri, timeout) -> new RoleScopedMcpClient.Session() {
                    @Override public Set<String> tools() { return Set.of("evidence.store"); }
                    @Override public String call(String tool, Map<String, Object> arguments) {
                        if (calls.incrementAndGet() == 1) throw new IllegalStateException("Evidence unavailable");
                        return "{\"uri\":\"evidence://task-1/attempt-1/agent-result/" + digest
                                + "\",\"digest\":\"" + digest
                                + "\",\"status\":\"COMPLETE\",\"classification\":\"INTERNAL\"}";
                    }
                    @Override public void close() { }
                }, mapper);
        AgentArtifactActivities.PublishCommand command = new AgentArtifactActivities.PublishCommand(
                "task-1", "attempt-1", "developer", "patch-proposal-v1", Set.of(),
                Base64.getEncoder().encodeToString(content), digest);

        assertThatThrownBy(() -> publisher.publish(command)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Evidence unavailable");
        publisher.publish(command);

        assertThat(calls).hasValue(2);
        assertThat(store.artifacts("task-1", "tenant-a", "orchestrator")).hasSize(1);
    }

    private InMemoryA2aTaskStore taskStore() {
        InMemoryA2aTaskStore store = new InMemoryA2aTaskStore();
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        store.createOrGet(new A2aTaskStore.StoredTask(
                "task-1", "context-1", "message-1", "a".repeat(64), "developer",
                "developer.code-task-v1", "orchestrator", "tenant-a", "delegation-1", now,
                A2aSendMessageService.TaskState.WORKING, 1, "{}", "workflow-1", "run-1"),
                new A2aTaskStore.HistoryRecord("message-1", "MESSAGE_ACCEPTED", now));
        return store;
    }

    private JsonNode fixtures() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("multiagents/fixtures/golden-contracts-v1.json")) {
            return mapper.readTree(input).path("documents");
        }
    }
}
