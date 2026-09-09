package com.example.aifactory.agentruntime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentInputEvidenceReaderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void materializesAnIntegratedPatchAsTextAfterVerifyingItsBinding() {
        byte[] patch = "diff --git a/A.java b/A.java\n".getBytes(StandardCharsets.UTF_8);
        String digest = Digests.sha256(patch);
        AgentInputEvidenceReader reader = reader(patch, digest);

        var result = reader.read("task-1", "attempt-1", new AgentInputEvidenceReader.Reference(
                "integrated-patch", "evidence://task-1/attempt-1/patch/" + digest,
                digest, patch.length, "integration-result-v1"), 1_024);

        assertThat(result.isTextual()).isTrue();
        assertThat(result.asText()).isEqualTo(new String(patch, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsNonJsonEvidenceForAnyOtherContract() {
        byte[] content = "not-json".getBytes(StandardCharsets.UTF_8);
        String digest = Digests.sha256(content);
        AgentInputEvidenceReader reader = reader(content, digest);

        assertThatThrownBy(() -> reader.read("task-1", "attempt-1", new AgentInputEvidenceReader.Reference(
                "result-1", "evidence://task-1/attempt-1/patch/" + digest,
                digest, content.length, "specialist-result-v1"), 1_024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A2A input Evidence is not valid JSON");
    }

    private AgentInputEvidenceReader reader(byte[] content, String digest) {
        String response;
        try {
            response = mapper.writeValueAsString(Map.of(
                    "uri", "evidence://task-1/attempt-1/patch/" + digest,
                    "digest", digest,
                    "status", "COMPLETE",
                    "size_bytes", content.length,
                    "content_base64", Base64.getEncoder().encodeToString(content)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        return new AgentInputEvidenceReader("independent-reviewer",
                new AgentMcpProperties(true, Duration.ofSeconds(1),
                        URI.create("http://repository-context-mcp:8091"), URI.create("http://evidence-mcp:8095")),
                (server, uri, timeout) -> new RoleScopedMcpClient.Session() {
                    @Override public Set<String> tools() { return Set.of("evidence.read"); }
                    @Override public String call(String tool, Map<String, Object> arguments) { return response; }
                    @Override public void close() { }
                }, mapper);
    }
}
